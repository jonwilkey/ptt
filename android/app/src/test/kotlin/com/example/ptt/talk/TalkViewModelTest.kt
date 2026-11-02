package com.example.ptt.talk

import androidx.test.core.app.ApplicationProvider
import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.FakeDevices
import com.example.ptt.alert.FakeNotices
import com.example.ptt.alert.FakeServiceControl
import com.example.ptt.alert.IncomingPage
import com.example.ptt.alert.LostPages
import com.example.ptt.audio.CaptureStatus
import com.example.ptt.data.ActivePageDao
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.TestDb
import com.example.ptt.net.ApiResult
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.net.CreatePageResponse
import com.example.ptt.net.NotEnrolledReason
import com.example.ptt.net.SessionEndReason
import com.example.ptt.net.SignedObjectDto
import com.example.ptt.outbox.Backoff
import com.example.ptt.outbox.FakePttApi
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxAction
import com.example.ptt.outbox.VirtualClock
import com.example.ptt.push.PageEvent
import com.example.ptt.push.SessionKeys
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val PAGE = "11111111-1111-4111-8111-111111111111"
private const val OTHER = "22222222-2222-4222-8222-222222222222"
private const val PEER = "33333333-3333-4333-8333-333333333333"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass")
class TalkViewModelTest {
    private lateinit var db: PttDatabase
    private val notices = FakeNotices()
    private val keys = SessionKeys()
    private val api = FakePttApi()
    private val events = MutableSharedFlow<PageEvent>(extraBufferCapacity = 8)

    private val urgent = IncomingPage(PAGE, "urgent", PEER, "Mom", "session-1")
    private val normal = IncomingPage(OTHER, "normal", PEER, "Dad", "session-2")

    @Before
    fun setUp() {
        db = TestDb.inMemory(ApplicationProvider.getApplicationContext())
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private class Rig(
        val vm: TalkViewModel,
        val engine: AlertEngine,
        val repo: ActivePageRepository,
        val cues: FakeCues
    )

    private fun TestScope.rig(
        dao: ActivePageDao = db.activePageDao(),
        talk: TalkSessions = NoTalk,
        audio: TalkAudioSource = FakeAudioSource(),
        cues: FakeCues = FakeCues()
    ): Rig {
        val clock = VirtualClock { currentTime }
        // The outbox's send loop only runs when a test calls runCurrent, so queued rows stay put until then.
        val outbox = Outbox(db.outboxDao(), api, Json, clock, { }, backgroundScope, Backoff(Random(1)))
        val repo = ActivePageRepository(dao, clock)
        val engine = AlertEngine(
            backgroundScope,
            FakeDevices().devices,
            notices,
            FakeServiceControl(),
            outbox,
            repo,
            keys
        )
        // The sender row of a lost page says `failed`: the app-wide owner of that, as in PttApplication.
        LostPages(outbox, repo, keys).start(backgroundScope)
        return Rig(
            TalkViewModel(engine, repo, outbox, keys, notices, events, backgroundScope, clock, talk, audio, cues),
            engine,
            repo,
            cues
        )
    }

    /** Reads of the active page wait for [gate] while it is set, to hold a coroutine at a chosen point. */
    private class GatedDao(private val real: ActivePageDao) : ActivePageDao by real {
        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun get(): ActivePageEntity? {
            gate?.await()
            return real.get()
        }
    }

    private suspend fun outboxKinds() = db.outboxDao().all().map { it.kind }

    @Test
    fun aNotificationTapAnswersOnceAndShowsTheAnsweredState() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        assertEquals(TalkPhase.ANSWERED, rig.vm.state.value.phase)
        assertEquals("Mom", rig.vm.state.value.caller)
        assertFalse(rig.engine.isAlerting)
        assertEquals(1, notices.count("clear:$PAGE"))
        assertEquals("answered", rig.repo.current()?.state)
        assertEquals("session-1", rig.repo.current()?.sessionId)
        // The same intent again, and a second tap, must not answer again.
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, "Mom")
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, "Mom")
        runCurrent()
        assertEquals(1, api.calls.count { it == "answer:$PAGE" })
        assertEquals(1, notices.count("clear:$PAGE"))
    }

    @Test
    fun aNewViewModelForAnAlreadyAnsweredPageDoesNotAnswerAgain() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        val second =
            TalkViewModel(
                rig.engine,
                rig.repo,
                rig.vm.outbox,
                keys,
                notices,
                events,
                backgroundScope,
                VirtualClock {
                    currentTime
                },
                NoTalk,
                FakeAudioSource(),
                FakeCues()
            )
        second.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        assertEquals(TalkPhase.ANSWERED, second.state.value.phase)
        assertEquals(1, api.calls.count { it == "answer:$PAGE" })
    }

    @Test
    fun theIncomingScreenWaitsForTheAnswerButtonAndTheAlertKeepsRinging() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_INCOMING, "Mom")
        runCurrent()
        assertEquals(TalkPhase.INCOMING, rig.vm.state.value.phase)
        assertTrue(rig.engine.isAlerting)
        assertEquals(emptyList<OutboxKind>(), outboxKinds())
        rig.vm.answerPressed()
        rig.vm.answerPressed()
        runCurrent()
        assertEquals(TalkPhase.ANSWERED, rig.vm.state.value.phase)
        assertFalse(rig.engine.isAlerting)
        assertEquals(1, api.calls.count { it == "answer:$PAGE" })
        assertEquals("answered", rig.repo.current()?.state)
    }

    @Test
    fun aTapAfterTheAnswerButtonDoesNotAnswerAgain() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_INCOMING, "Mom")
        runCurrent()
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, "Mom")
        rig.vm.answerPressed()
        runCurrent()
        assertEquals(1, api.calls.count { it == "answer:$PAGE" })
    }

    @Test
    fun aLaterIntentForAnotherPageReplacesTheScreenAndLeavesTheFirstPageAlone() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        rig.engine.start(normal)
        rig.vm.show(PAGE, TalkIntents.MODE_INCOMING, "Mom")
        runCurrent()
        rig.vm.show(OTHER, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        assertEquals(OTHER, rig.vm.state.value.pageId)
        assertEquals("Dad", rig.vm.state.value.caller)
        assertEquals(TalkPhase.ANSWERED, rig.vm.state.value.phase)
        assertTrue(rig.engine.knows(PAGE))
        assertTrue(rig.engine.isAlerting)
        assertEquals(listOf("answer:$OTHER"), api.calls)
    }

    @Test
    fun endQueuesACancelAndForgetsThePage() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        keys.put(PAGE, ByteArray(32) { 1 })
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        rig.vm.end()
        rig.vm.end()
        runCurrent()
        assertEquals(1, api.calls.count { it == "cancel:$PAGE" })
        assertEquals(1, api.calls.count { it == "answer:$PAGE" })
        assertNull(rig.repo.current())
        assertFalse(keys.has(PAGE))
        assertEquals(2, notices.count("clear:$PAGE"))
        // Our own End deleting the row is not "the page ended".
        assertEquals(TalkPhase.DONE, rig.vm.state.value.phase)
    }

    @Test(timeout = 60_000)
    fun endAlwaysQueuesTheRestCancelWhetherOrNotTheSocketSentSessionEnd() = runTest {
        for (sentOverSocket in listOf(true, false)) {
            val talk = RecordingTalk(sentOverSocket)
            val before = api.calls.count { it == "cancel:$PAGE" }
            val rig = rig(talk = talk)
            rig.engine.start(urgent)
            keys.put(PAGE, ByteArray(32) { 1 })
            rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
            runCurrent()
            rig.vm.end()
            runCurrent()
            assertEquals(listOf(PAGE), talk.ends)
            assertEquals(1, api.calls.count { it == "cancel:$PAGE" } - before)
            assertEquals("no error is shown for the cancel", TalkPhase.DONE, rig.vm.state.value.phase)
        }
    }

    @Test
    fun theRowGoingAwayEndsTheScreen() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        rig.repo.start(PAGE, PageRole.RECEIVER, "session-1", PEER, "urgent", "ringing")
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        assertEquals(TalkPhase.ANSWERED, rig.vm.state.value.phase)
        rig.repo.end(PAGE)
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
        // Over stays over: a late tap does nothing.
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        rig.vm.end()
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
    }

    @Test
    fun anAnsweredElsewherePushRefinesTheEndedLine() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_INCOMING, null)
        runCurrent()
        // The dispatcher deletes the row, then publishes the event.
        rig.repo.start(PAGE, PageRole.RECEIVER, "session-1", PEER, "urgent", "ringing")
        runCurrent()
        rig.repo.end(PAGE)
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
        events.tryEmit(PageEvent.Ended(PAGE, "answered_elsewhere"))
        runCurrent()
        assertEquals(TalkPhase.ANSWERED_ELSEWHERE, rig.vm.state.value.phase)
    }

    @Test
    fun aPushedCancelWithoutARowEndsTheScreen() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_INCOMING, null)
        runCurrent()
        events.tryEmit(PageEvent.Ended(OTHER, "cancelled"))
        runCurrent()
        assertEquals(TalkPhase.INCOMING, rig.vm.state.value.phase)
        events.tryEmit(PageEvent.Ended(PAGE, "cancelled"))
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
    }

    @Test
    fun aFinal409AnswerOutcomeMeansAnsweredElsewhere() = runTest {
        api.actionResults += ApiResult.Final(409, "already_answered")
        val rig = rig()
        rig.engine.start(urgent)
        keys.put(PAGE, ByteArray(32) { 1 })
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        assertEquals(TalkPhase.ANSWERED_ELSEWHERE, rig.vm.state.value.phase)
        assertNull(rig.repo.current())
        assertFalse(keys.has(PAGE))
    }

    @Test
    fun aFinal410AnswerOutcomeMeansTheEndedLine() = runTest {
        api.actionResults += ApiResult.Final(410, "page_ended")
        val rig = rig()
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
    }

    @Test
    fun aPageThatIsAlreadyOverIsNotAnswered() = runTest {
        val rig = rig()
        rig.repo.start(PAGE, PageRole.RECEIVER, "session-1", PEER, "urgent", "ended")
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, "Mom")
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
        assertEquals(emptyList<OutboxKind>(), outboxKinds())
    }

    @Test
    fun aPageNobodyKnowsIsStillAnsweredWithTheIntentsName() = runTest {
        val rig = rig()
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, "Mom")
        runCurrent()
        assertEquals(TalkPhase.ANSWERED, rig.vm.state.value.phase)
        assertEquals("Mom", rig.vm.state.value.caller)
        assertEquals(1, api.calls.count { it == "answer:$PAGE" })
        assertNull(rig.repo.current())
    }

    // --- the sender's progress ---

    private fun pageRequest() = CreatePageRequest(PAGE, PEER, "normal", "session-1", SignedObjectDto("p", OTHER, "s"))

    /** The state a sender screen starts from: the row [PageSender] stores, with the page row waiting in the outbox. */
    private suspend fun Rig.sendPage(level: String = "normal") {
        keys.put(PAGE, ByteArray(32) { 1 })
        repo.start(PAGE, PageRole.SENDER, "session-1", PEER, level, "sending")
        vm.outbox.enqueue(OutboxAction.Page(pageRequest()))
    }

    @Test
    fun theSendersScreenFollowsTheRowForwardOnly() = runTest {
        val rig = rig()
        rig.sendPage("urgent")
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        assertEquals(TalkPhase.OUTGOING, rig.vm.state.value.phase)
        assertEquals(SendProgress.SENDING, rig.vm.state.value.progress)
        assertTrue(rig.vm.state.value.urgent)
        for ((state, progress) in listOf(
            "not_delivered" to SendProgress.NOT_DELIVERED,
            "delivered" to SendProgress.DELIVERED,
            "acknowledged" to SendProgress.ACKNOWLEDGED,
            // A late, older state never moves the screen back.
            "delivered" to SendProgress.ACKNOWLEDGED,
            "answered" to SendProgress.ANSWERED
        )) {
            rig.repo.updateState(PAGE, state)
            runCurrent()
            assertEquals(state, progress, rig.vm.state.value.progress)
        }
        assertEquals("Mom", rig.vm.state.value.caller)
    }

    @Test
    fun pageEventsMoveTheSendersScreenToo() = runTest {
        val rig = rig()
        rig.sendPage()
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        events.tryEmit(PageEvent.State(PAGE, "delivered"))
        events.tryEmit(PageEvent.State(OTHER, "acknowledged"))
        runCurrent()
        assertEquals(SendProgress.DELIVERED, rig.vm.state.value.progress)
        events.tryEmit(PageEvent.Answered(PAGE))
        runCurrent()
        assertEquals(SendProgress.ANSWERED, rig.vm.state.value.progress)
    }

    @Test
    fun noAnswerShowsWhicheverOfTheRowAndTheEventComesFirst() = runTest {
        for (eventFirst in listOf(true, false)) {
            val rig = rig()
            rig.repo.start(PAGE, PageRole.SENDER, "session-1", PEER, "normal", "delivered")
            rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
            runCurrent()
            // The dispatcher deletes the row of a missed page, then publishes the event.
            rig.repo.end(PAGE)
            if (eventFirst) events.tryEmit(PageEvent.State(PAGE, "missed"))
            runCurrent()
            if (!eventFirst) {
                assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
                events.tryEmit(PageEvent.State(PAGE, "missed"))
                runCurrent()
            }
            assertEquals(TalkPhase.OUTGOING, rig.vm.state.value.phase)
            assertEquals(SendProgress.NO_ANSWER, rig.vm.state.value.progress)
            assertFalse(rig.vm.state.value.over)
        }
    }

    @Test
    fun theAnswererEndingShowsPageEnded() = runTest {
        val rig = rig()
        rig.repo.start(PAGE, PageRole.SENDER, "session-1", PEER, "normal", "answered")
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        assertEquals(SendProgress.ANSWERED, rig.vm.state.value.progress)
        rig.repo.end(PAGE)
        events.tryEmit(PageEvent.State(PAGE, "ended"))
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
    }

    @Test
    fun aPageTheServerEndedAtOnceFails() = runTest {
        api.pageResults += ApiResult.Success(CreatePageResponse(PAGE, "ended"))
        val rig = rig()
        rig.sendPage()
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        assertEquals(SendProgress.FAILED, rig.vm.state.value.progress)
        assertEquals("failed", rig.repo.current()?.state)
        assertFalse(keys.has(PAGE))
    }

    @Test
    fun aPageTheServerRefusedFails() = runTest {
        api.pageResults += ApiResult.Final(403, "forbidden")
        val rig = rig()
        rig.sendPage()
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        assertEquals(SendProgress.FAILED, rig.vm.state.value.progress)
        assertEquals("failed", rig.repo.current()?.state)
    }

    @Test
    fun aPageThatRanOutOfItsSendWindowFailsEvenWithNoScreenOpen() = runTest {
        api.pageResults += ApiResult.Retryable(503)
        val rig = rig()
        rig.sendPage()
        runCurrent()
        advanceTimeBy(Outbox.PAGE_SEND_WINDOW_MS + 1_000)
        runCurrent()
        assertEquals("failed", rig.repo.current()?.state)
        assertFalse(keys.has(PAGE))
        // Reopened later, the screen shows the final state from the row.
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        assertEquals(SendProgress.FAILED, rig.vm.state.value.progress)
    }

    @Test
    fun reopeningAPageThatIsOverShowsPageEnded() = runTest {
        val rig = rig()
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
        assertEquals(emptyList<OutboxKind>(), outboxKinds())
    }

    @Test
    fun endBeforeThePageWasSentCollapsesLocally() = runTest {
        // The phone is not enrolled (yet): the page row stays in the outbox, never sent (attempts == 0).
        api.pageResults += ApiResult.NotEnrolled(NotEnrolledReason.NOT_ENROLLED)
        val rig = rig()
        rig.sendPage()
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        assertEquals(listOf(OutboxKind.PAGE), outboxKinds())
        rig.vm.end()
        rig.vm.end()
        runCurrent()
        // No cancel went out and the page is gone: nothing will ever ring.
        assertEquals(listOf("page:$PAGE"), api.calls)
        assertEquals(emptyList<OutboxKind>(), outboxKinds())
        assertNull(rig.repo.current())
        assertFalse(keys.has(PAGE))
        assertEquals(1, notices.count("clear:$PAGE"))
        assertEquals(TalkPhase.DONE, rig.vm.state.value.phase)
    }

    @Test
    fun endAfterThePageWasSentCancelsItOnTheServerInOrder() = runTest {
        val rig = rig()
        rig.sendPage()
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        rig.repo.updateState(PAGE, "delivered")
        rig.vm.end()
        runCurrent()
        assertEquals(listOf("page:$PAGE", "cancel:$PAGE"), api.calls)
        assertNull(rig.repo.current())
        assertEquals(TalkPhase.DONE, rig.vm.state.value.phase)
    }

    @Test
    fun endOnALostPageOnlyCleansUp() = runTest {
        api.pageResults += ApiResult.Final(403, "forbidden")
        val rig = rig()
        rig.sendPage()
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        rig.vm.end()
        runCurrent()
        assertEquals(listOf("page:$PAGE"), api.calls)
        assertNull(rig.repo.current())
        assertEquals(TalkPhase.DONE, rig.vm.state.value.phase)
    }

    @Test
    fun aReceiverRowOfTheSamePageDoesNotMoveTheSendersScreen() = runTest {
        val rig = rig()
        rig.repo.start(PAGE, PageRole.SENDER, "session-1", PEER, "normal", "sending")
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        rig.repo.start(PAGE, PageRole.RECEIVER, "session-1", PEER, "normal", "answered")
        runCurrent()
        assertEquals(SendProgress.SENDING, rig.vm.state.value.progress)
        assertEquals(TalkPhase.OUTGOING, rig.vm.state.value.phase)
    }

    @Test
    fun endWaitsForTheAnswerSoACancelNeverOvertakesIt() = runTest {
        val dao = GatedDao(db.activePageDao())
        val rig = rig(dao)
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_INCOMING, "Mom")
        runCurrent()
        // The answer now stalls reading the row; End is pressed meanwhile.
        val gate = CompletableDeferred<Unit>()
        dao.gate = gate
        rig.vm.answerPressed()
        runCurrent()
        assertEquals(TalkPhase.ANSWERED, rig.vm.state.value.phase)
        rig.vm.end()
        runCurrent()
        assertEquals(emptyList<String>(), api.calls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("answer:$PAGE", "cancel:$PAGE"), api.calls)
        assertNull(rig.repo.current())
        assertEquals(TalkPhase.DONE, rig.vm.state.value.phase)
    }

    @Test
    fun anOpenThatFinishesAfterAnotherPageReplacedItDoesNotMarkTheNewPagesRowSeen() = runTest {
        val dao = GatedDao(db.activePageDao())
        val rig = rig(dao)
        rig.repo.start(PAGE, PageRole.RECEIVER, "session-1", PEER, "urgent", "ringing")
        val gate = CompletableDeferred<Unit>()
        dao.gate = gate
        rig.vm.show(PAGE, TalkIntents.MODE_INCOMING, "Mom")
        rig.vm.show(OTHER, TalkIntents.MODE_INCOMING, "Dad")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        // A row change that is not the new page's row must not end the new page's screen.
        rig.repo.updateState(PAGE, "acknowledged")
        runCurrent()
        assertEquals(OTHER, rig.vm.state.value.pageId)
        assertEquals(TalkPhase.INCOMING, rig.vm.state.value.phase)
    }

    @Test
    fun anyFinalRefusalOfTheAnswerEndsTheScreen() = runTest {
        api.actionResults += ApiResult.Final(403, "forbidden")
        val rig = rig()
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        assertEquals(TalkPhase.ENDED, rig.vm.state.value.phase)
    }

    @Test
    fun aRefusedAnswerIsCleanedUpEvenWithNoScreenOpen() = runTest {
        api.actionResults += ApiResult.Final(410, "page_ended")
        val rig = rig()
        rig.engine.start(urgent)
        keys.put(PAGE, ByteArray(32) { 1 })
        rig.engine.answer(PAGE)
        runCurrent()
        assertNull(rig.repo.current())
        assertFalse(keys.has(PAGE))
    }

    @Test
    fun aLateEndedInTheCreateResponseDoesNotFailAPageThatAlreadyRan() = runTest {
        api.pageResults += ApiResult.Success(CreatePageResponse(PAGE, "ended"))
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val rig = rig()
        rig.sendPage()
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        // The page was answered while the request (a retry of one the server already ran) was in flight.
        rig.repo.updateState(PAGE, "answered")
        events.tryEmit(PageEvent.Answered(PAGE))
        runCurrent()
        assertEquals(SendProgress.ANSWERED, rig.vm.state.value.progress)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("page:$PAGE"), api.calls)
        assertEquals(SendProgress.ANSWERED, rig.vm.state.value.progress)
        assertEquals("answered", rig.repo.current()?.state)
    }

    @Test
    fun aSendingRowOlderThanTheSendWindowShowsFailedOnOpen() = runTest {
        val rig = rig()
        rig.repo.start(PAGE, PageRole.SENDER, "session-1", PEER, "normal", "sending")
        advanceTimeBy(Outbox.PAGE_SEND_WINDOW_MS + 1_000)
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        assertEquals(SendProgress.FAILED, rig.vm.state.value.progress)
    }

    // ---- live talk screen ----

    private class Live(val rig: Rig, val talk: FakeTalk, val audio: FakeAudioSource) {
        val cues: FakeCues get() = rig.cues
    }

    private fun TestScope.receiverLive(): Live {
        val talk = FakeTalk()
        val audio = FakeAudioSource(PAGE)
        val rig = rig(talk = talk, audio = audio)
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        return Live(rig, talk, audio)
    }

    private suspend fun TestScope.senderLive(): Live {
        val talk = FakeTalk()
        val audio = FakeAudioSource(PAGE)
        val rig = rig(talk = talk, audio = audio)
        rig.repo.start(PAGE, PageRole.SENDER, "session-1", PEER, "normal", "answered")
        rig.vm.show(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
        runCurrent()
        return Live(rig, talk, audio)
    }

    private fun conversation(peer: Boolean = true, rtt: Long? = 100) = TalkSessionState(
        pageId = PAGE,
        stage = SessionStage.CONVERSATION,
        connection = TalkConnection.LIVE,
        peerPresent = peer,
        rttMillis = rtt
    )

    @Test
    fun receiverTalkIsEnabledOnlyForALivePresentConversationOfTheShownPage() = runTest {
        val l = receiverLive()
        val vm = l.rig.vm
        assertTrue(vm.live.value.active)
        assertFalse(vm.live.value.talkEnabled)
        l.talk.state.value = conversation()
        runCurrent()
        assertTrue(vm.live.value.talkEnabled)
        for (off in listOf(
            conversation(peer = false),
            conversation().copy(connection = TalkConnection.CONNECTING),
            conversation().copy(connection = TalkConnection.UNAVAILABLE),
            conversation().copy(stage = SessionStage.WAITING),
            conversation().copy(pageId = OTHER),
            conversation().copy(pageId = null)
        )) {
            l.talk.state.value = off
            runCurrent()
            assertFalse("$off", vm.live.value.talkEnabled)
        }
    }

    @Test
    fun aStaleSessionOfAnotherPageShowsNothing() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation(rtt = 50).copy(pageId = OTHER, floor = TalkFloor.PEER_TALKING)
        runCurrent()
        val live = l.rig.vm.live.value
        assertEquals(TalkConnection.IDLE, live.connection)
        assertEquals(Speaker.NONE, live.speaker)
        assertNull(live.quality)
    }

    @Test
    fun statusLinesFollowTheSessionState() = runTest {
        val l = receiverLive()
        val vm = l.rig.vm
        l.talk.state.value = conversation().copy(connection = TalkConnection.CONNECTING)
        runCurrent()
        assertEquals(TalkConnection.CONNECTING, vm.live.value.connection)
        l.talk.state.value = conversation(rtt = 299)
        runCurrent()
        assertEquals(LinkQuality.GOOD, vm.live.value.quality)
        l.talk.state.value = conversation(rtt = 300)
        runCurrent()
        assertEquals(LinkQuality.FAIR, vm.live.value.quality)
        l.talk.state.value = conversation(rtt = 799)
        runCurrent()
        assertEquals(LinkQuality.FAIR, vm.live.value.quality)
        l.talk.state.value = conversation(rtt = 800)
        runCurrent()
        assertEquals(LinkQuality.POOR, vm.live.value.quality)
        l.talk.state.value = conversation(rtt = null)
        runCurrent()
        assertNull(vm.live.value.quality)
        l.talk.state.value = conversation().copy(floor = TalkFloor.YOU)
        runCurrent()
        assertEquals(Speaker.YOU, vm.live.value.speaker)
        l.talk.state.value = conversation().copy(floor = TalkFloor.PEER_TALKING)
        runCurrent()
        assertEquals(Speaker.PEER, vm.live.value.speaker)
        l.talk.state.value = conversation().copy(floor = TalkFloor.BUSY)
        runCurrent()
        assertEquals(Speaker.NONE, vm.live.value.speaker)
        l.talk.state.value = conversation(peer = false)
        runCurrent()
        assertTrue(vm.live.value.waitingForPeer)
        l.talk.state.value = conversation().copy(connection = TalkConnection.UNAVAILABLE)
        runCurrent()
        assertFalse(vm.live.value.waitingForPeer)
        assertEquals(TalkPhase.ANSWERED, vm.state.value.phase)
    }

    @Test
    fun selfPageHasNoLiveUi() = runTest {
        val talk = FakeTalk(isEligible = false)
        val rig = rig(talk = talk, audio = FakeAudioSource(PAGE))
        rig.engine.start(urgent)
        rig.vm.show(PAGE, TalkIntents.MODE_ANSWER, null)
        runCurrent()
        talk.state.value = conversation()
        runCurrent()
        assertFalse(rig.vm.live.value.active)
        assertFalse(rig.vm.live.value.talkEnabled)
    }

    @Test
    fun theSessionsFirstTalkerLockDisablesTalk() = runTest {
        val l = senderLive()
        val vm = l.rig.vm
        l.talk.state.value = conversation().copy(firstTalkerLocked = true)
        runCurrent()
        assertTrue(vm.live.value.firstTalkerLocked)
        assertFalse(vm.live.value.talkEnabled)
        assertFalse(vm.pressTalk())
        assertEquals(emptyList<String>(), l.audio.calls)
        l.talk.state.value = conversation()
        runCurrent()
        assertFalse(vm.live.value.firstTalkerLocked)
        assertTrue(vm.live.value.talkEnabled)
    }

    @Test
    fun sessionEventsPlayTheirCues() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation()
        runCurrent()
        l.talk.events.emit(TalkEvent.PeerJoined)
        runCurrent()
        assertEquals(listOf(Cue.JOINED), l.cues.played)
        l.talk.events.emit(TalkEvent.FloorGrantedToYou)
        runCurrent()
        assertEquals(Cue.GO, l.cues.played.last())
        l.talk.events.emit(TalkEvent.FloorReleased(yours = false))
        runCurrent()
        assertEquals(Cue.OVER, l.cues.played.last())
        l.talk.events.emit(TalkEvent.FloorBusy)
        runCurrent()
        assertEquals(Cue.BUSY, l.cues.played.last())
        l.talk.events.emit(TalkEvent.NoPeer)
        runCurrent()
        assertEquals(listOf(Cue.JOINED, Cue.GO, Cue.OVER, Cue.BUSY, Cue.BUSY), l.cues.played)
    }

    @Test
    fun aFloorYouReleasedAndUnrelatedEventsPlayNoCue() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation()
        runCurrent()
        l.talk.events.emit(TalkEvent.FloorReleased(yours = true))
        l.talk.events.emit(TalkEvent.PeerLeft)
        l.talk.events.emit(TalkEvent.PeerStartedTalking)
        l.talk.events.emit(TalkEvent.StopCapture)
        l.talk.events.emit(TalkEvent.TalkUnavailable)
        runCurrent()
        assertEquals(emptyList<Cue>(), l.cues.played)
    }

    @Test
    fun eventsForAnotherPagePlayNoCue() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation().copy(pageId = OTHER)
        runCurrent()
        l.talk.events.emit(TalkEvent.PeerJoined)
        l.talk.events.emit(TalkEvent.FloorGrantedToYou)
        l.talk.events.emit(TalkEvent.FloorBusy)
        runCurrent()
        assertEquals(emptyList<Cue>(), l.cues.played)
    }

    @Test
    fun aFreshPressWhileThePeerTalksBuzzesButDoesNotPress() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation().copy(floor = TalkFloor.PEER_TALKING)
        runCurrent()
        assertTrue(l.rig.vm.live.value.talkEnabled)
        assertFalse(l.rig.vm.pressTalk())
        assertEquals(listOf(Cue.BUSY), l.cues.played)
        assertEquals(emptyList<String>(), l.audio.calls)
        // The buzz is not a hold: releasing changes nothing.
        l.rig.vm.releaseTalk(HoldSource.BUTTON)
        assertEquals(emptyList<String>(), l.audio.calls)
    }

    @Test
    fun pressAndReleaseReachTheAudioOnlyWhileTalkIsEnabled() = runTest {
        val l = receiverLive()
        val vm = l.rig.vm
        assertFalse(vm.pressTalk())
        assertEquals(emptyList<String>(), l.audio.calls)
        l.talk.state.value = conversation()
        runCurrent()
        assertTrue(vm.pressTalk())
        assertFalse("a second press while held is not a new request", vm.pressTalk())
        vm.releaseTalk(HoldSource.BUTTON)
        vm.releaseTalk(HoldSource.BUTTON)
        assertEquals(listOf("press", "release"), l.audio.calls)
    }

    @Test
    fun aRefusedPressIsNotARequestAndNeedsNoRelease() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation()
        runCurrent()
        l.audio.pressResult = false
        assertFalse(l.rig.vm.pressTalk())
        l.rig.vm.releaseTalk(HoldSource.BUTTON)
        assertEquals(listOf("press"), l.audio.calls)
    }

    @Test
    fun noPressWhenTheServiceHostsAnotherPage() = runTest {
        val l = receiverLive()
        l.audio.hosted = OTHER
        l.talk.state.value = conversation()
        runCurrent()
        assertFalse(l.rig.vm.pressTalk())
        assertEquals(emptyList<String>(), l.audio.calls)
    }

    @Test
    fun talkBecomingDisabledWhileHeldReleases() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation()
        runCurrent()
        assertTrue(l.rig.vm.pressTalk())
        l.talk.state.value = conversation().copy(connection = TalkConnection.CONNECTING)
        runCurrent()
        assertEquals(listOf("press", "release"), l.audio.calls)
    }

    @Test
    fun theCaptureStatusIsShownAndTalkingComesOnlyFromTheFloor() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation()
        l.audio.capture.value = CaptureStatus.STOPPED_AT_CAP
        runCurrent()
        assertEquals(CaptureStatus.STOPPED_AT_CAP, l.rig.vm.live.value.capture)
        assertEquals(Speaker.NONE, l.rig.vm.live.value.speaker)
        assertEquals(emptyList<String>(), l.audio.calls)
    }

    @Test
    fun anIdleSessionEndClosesWithItsOwnLine() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation()
        runCurrent()
        l.talk.events.emit(TalkEvent.SessionEnded(SessionEndReason.IDLE))
        runCurrent()
        assertEquals(TalkPhase.ENDED, l.rig.vm.state.value.phase)
        assertEquals(SessionEndReason.IDLE, l.rig.vm.state.value.endReason)
        assertFalse(l.rig.vm.live.value.talkEnabled)
    }

    @Test
    fun aPeerEndClosesTheSenderToo() = runTest {
        val l = senderLive()
        l.talk.state.value = conversation()
        runCurrent()
        l.talk.events.emit(TalkEvent.SessionEnded(SessionEndReason.ENDED_BY_PEER))
        runCurrent()
        assertEquals(TalkPhase.ENDED, l.rig.vm.state.value.phase)
        assertEquals(SessionEndReason.ENDED_BY_PEER, l.rig.vm.state.value.endReason)
    }

    @Test
    fun endSendsSessionEndAndCancelAndReleasesTheHold() = runTest {
        val l = receiverLive()
        l.talk.state.value = conversation()
        runCurrent()
        l.rig.vm.pressTalk()
        l.rig.vm.end()
        runCurrent()
        assertEquals(listOf(PAGE), l.talk.ends)
        assertEquals(listOf("press", "release"), l.audio.calls)
        assertEquals(1, api.calls.count { it == "cancel:$PAGE" })
        assertEquals(TalkPhase.DONE, l.rig.vm.state.value.phase)
    }
}

/** A view model test has no live session: End goes through the outbox. */
object NoTalk : TalkSessions {
    override fun eligible(pageId: String) = true

    override fun requestEnd(pageId: String) = false

    override val state = MutableStateFlow(TalkSessionState())

    override val events = MutableSharedFlow<TalkEvent>()
}

/** A live session whose WebSocket `session_end` is queued (or not) and which records it. */
class RecordingTalk(private val sent: Boolean) : TalkSessions {
    val ends = mutableListOf<String>()

    override fun eligible(pageId: String) = true

    override val state = MutableStateFlow(TalkSessionState())

    override val events = MutableSharedFlow<TalkEvent>(extraBufferCapacity = 16)

    override fun requestEnd(pageId: String): Boolean {
        ends += pageId
        return sent
    }
}

/** A live session the test drives by hand. */
class FakeTalk(var isEligible: Boolean = true) : TalkSessions {
    val ends = mutableListOf<String>()

    override fun eligible(pageId: String) = isEligible

    override val state = MutableStateFlow(TalkSessionState())

    override val events = MutableSharedFlow<TalkEvent>(extraBufferCapacity = 16)

    override fun requestEnd(pageId: String): Boolean {
        ends += pageId
        return true
    }
}

/** An audio seam that records presses and releases of the hosted page. */
class FakeAudioSource(var hosted: String? = null) : TalkAudioSource {
    val calls = mutableListOf<String>()
    val capture = MutableStateFlow(CaptureStatus.IDLE)
    var pressResult = true

    private val port = object : TalkAudioPort {
        override fun press(): Boolean {
            calls += "press"
            return pressResult
        }

        override fun release() {
            calls += "release"
        }

        override val captureStatus: StateFlow<CaptureStatus> get() = capture
    }

    override fun audio(pageId: String): TalkAudioPort? = port.takeIf { hosted == pageId }

    override fun audios(pageId: String): Flow<TalkAudioPort?> = flowOf(audio(pageId))
}

/** A cues seam that records the cues the view model asks to play. */
class FakeCues : TalkCues {
    val played = mutableListOf<Cue>()

    override fun play(cue: Cue) {
        played += cue
    }
}
