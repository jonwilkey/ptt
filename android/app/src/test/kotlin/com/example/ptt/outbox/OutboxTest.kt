package com.example.ptt.outbox

import androidx.test.core.app.ApplicationProvider
import com.example.ptt.data.OutboxEntity
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.TestDb
import com.example.ptt.net.ApiResult
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.net.CreatePageResponse
import com.example.ptt.net.DeliveredResponse
import com.example.ptt.net.NotEnrolledReason
import com.example.ptt.net.SignedObjectDto
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A [Clock] that reads the test scheduler's virtual time, so rows' timestamps follow `advanceTimeBy`. */
class VirtualClock(private val now: () -> Long) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(now())

    override fun millis(): Long = now()
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OutboxTest {
    private lateinit var db: PttDatabase
    private val api = FakePttApi()
    private var scheduled = 0
    private val json = Json { explicitNulls = false }

    @Before
    fun setUp() {
        db = TestDb.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun TestScope.outbox(scope: CoroutineScope = backgroundScope, fakeApi: FakePttApi = api) = Outbox(
        dao = db.outboxDao(),
        api = fakeApi,
        json = json,
        clock = VirtualClock { currentTime },
        scheduler = { scheduled++ },
        scope = scope,
        backoff = Backoff(Random(42))
    )

    private fun page(id: String = PAGE) =
        CreatePageRequest(id, "user", "urgent", "session", SignedObjectDto("cGF5", "device", "c2ln"))

    private suspend fun rows() = db.outboxDao().all()

    @Test
    fun enqueuePersistsSchedulesWorkAndSendsImmediately() = runTest {
        val outbox = outbox()
        val id = outbox.enqueue(OutboxAction.Page(page()))
        assertEquals(PAGE, id)
        assertEquals(1, scheduled)
        runCurrent()
        assertEquals(listOf("page:$PAGE"), api.calls)
        assertTrue("row deleted when accepted", rows().isEmpty())
    }

    @Test
    fun acceptedRowPublishesAnOutcome() = runTest {
        val outbox = outbox()
        val events = mutableListOf<OutboxOutcome>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { outbox.outcomes.toList(events) }
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        assertEquals(
            listOf(OutboxOutcome(PAGE, OutboxKind.PAGE, PAGE, OutboxOutcome.Result.Sent("ringing"))),
            events
        )
    }

    @Test
    fun serverErrorsAndNetworkFailuresRetryWithBackoffAndTheSamePageBody() = runTest {
        api.pageResults += ApiResult.Retryable(500)
        api.pageResults += ApiResult.Retryable(null, cause = IOException("offline"))
        api.pageResults += ApiResult.Success(CreatePageResponse(PAGE, "ringing"))
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        assertEquals(1, api.calls.size)
        var row = rows().single()
        assertEquals(1, row.attempts)
        // First retry: 1 s base, jittered down by at most 25 %.
        assertTrue(row.nextAttemptAt in 750..1_000)

        advanceTimeBy(row.nextAttemptAt - currentTime - 1)
        runCurrent()
        assertEquals("not before it is due", 1, api.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, api.calls.size)
        row = rows().single()
        assertEquals(2, row.attempts)
        assertTrue("second wait is ~2 s", row.nextAttemptAt - currentTime in 1_500..2_000)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, api.calls.size)
        assertTrue(rows().isEmpty())
        // Every attempt carried the identical request: same page id (the idempotency key), same body.
        assertEquals(List(3) { page() }, api.pageRequests)
    }

    @Test
    fun backoffNeverExceedsTheSixtySecondCap() {
        val backoff = Backoff(Random(1))
        for (attempts in 1..40) {
            val delay = backoff.delayMs(attempts)
            val ceiling = minOf(1_000L shl (attempts - 1).coerceAtMost(16), 60_000L)
            assertTrue("attempt $attempts: $delay", delay in (ceiling * 3 / 4)..ceiling)
        }
        assertEquals(60_000L, Backoff(Random(1), jitter = 0.0).delayMs(30))
    }

    @Test
    fun retryAfterIsHonoured() = runTest {
        api.pageResults += ApiResult.Retryable(429, retryAfterMs = 30_000)
        api.pageResults += ApiResult.Success(CreatePageResponse(PAGE, "ringing"))
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        assertEquals(30_000L, rows().single().nextAttemptAt)

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, api.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, api.calls.size)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun finalErrorsDeleteTheRowWithoutRetry() = runTest {
        val finals = listOf(
            ApiResult.Final(410, "page_ended"),
            ApiResult.Final(409, "already_answered"),
            ApiResult.Final(400, "invalid_request"),
            ApiResult.Final(401, "unauthorized"),
            ApiResult.Final(404, "not_found")
        )
        val outbox = outbox()
        val events = mutableListOf<OutboxOutcome>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { outbox.outcomes.toList(events) }
        for (final in finals) {
            api.actionResults.clear()
            api.actionResults += final
            outbox.enqueue(OutboxAction.Answer(PAGE))
            runCurrent()
            assertTrue("${final.status} leaves no row", rows().isEmpty())
        }
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("no retries", finals.size, api.calls.size)
        assertEquals(
            finals.map { OutboxOutcome.Result.Rejected(it.status, it.code) },
            events.map { it.result }
        )
    }

    @Test
    fun everyKindCallsItsOwnEndpoint() = runTest {
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Delivered(PAGE))
        outbox.enqueue(OutboxAction.Acknowledge(PAGE))
        outbox.enqueue(OutboxAction.Answer(PAGE))
        outbox.enqueue(OutboxAction.Cancel(PAGE))
        runCurrent()
        assertEquals(listOf("delivered", "acknowledge", "answer", "cancel").map { "$it:$PAGE" }, api.calls)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun enqueueingTheSamePageTwiceKeepsOneRow() = runTest {
        api.pageResults += ApiResult.Retryable(503)
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        assertEquals(1, rows().size)
    }

    @Test
    fun aCancelNeverOvertakesItsUnsentPage() = runTest {
        api.pageResults += ApiResult.Retryable(503)
        api.pageResults += ApiResult.Success(CreatePageResponse(PAGE, "ringing"))
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        advanceTimeBy(1)
        outbox.enqueue(OutboxAction.Cancel(PAGE))
        runCurrent()
        assertEquals("only the page was attempted", listOf("page:$PAGE"), api.calls)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("page:$PAGE", "page:$PAGE", "cancel:$PAGE"), api.calls)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun anotherPagesRowsAreNotBlocked() = runTest {
        api.pageResults += ApiResult.Retryable(503)
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        outbox.enqueue(OutboxAction.Cancel("other-page"))
        runCurrent()
        assertTrue("cancel:other-page" in api.calls)
        assertEquals(listOf(PAGE), rows().map { it.pageId })
    }

    @Test
    fun notEnrolledKeepsRowsAndStopsUntilKicked() = runTest {
        api.actionResults += ApiResult.NotEnrolled(NotEnrolledReason.NEEDS_REENROLLMENT)
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Acknowledge(PAGE))
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("held, not hammered", 1, api.calls.size)
        assertEquals(1, rows().size)
        assertEquals(DrainResult(0L, blocked = true).blocked, outbox.drain().blocked)

        api.actionResults.clear()
        outbox.kick()
        runCurrent()
        assertTrue(rows().isEmpty())
    }

    @Test
    fun rowsSurviveProcessDeathAndAreSentByTheNextProcess() = runTest {
        api.pageResults += ApiResult.Retryable(null, cause = IOException("offline"))
        val firstProcess = Job()
        val outbox = outbox(scope = CoroutineScope(firstProcess + UnconfinedTestDispatcher(testScheduler)))
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        firstProcess.cancel() // "process death": the in-memory loop is gone, the row is not
        assertEquals(1, rows().size)

        advanceTimeBy(2_000)
        val secondApi = FakePttApi()
        val restarted = outbox(fakeApi = secondApi)
        val result = restarted.drain() // what the expedited worker does
        assertEquals(listOf("page:$PAGE"), secondApi.calls)
        assertEquals(DrainResult(null, blocked = false), result)
        assertTrue(rows().isEmpty())
    }

    // ---- delivered (protocol section 9) ----

    @Test
    fun deliveredReturnsTheServerStateWithoutQueueing() = runTest {
        val outbox = outbox()
        for ((state, expected) in listOf(
            "ringing" to DeliveredOutcome.Ringing,
            "acknowledged" to DeliveredOutcome.Acknowledged,
            "answered" to DeliveredOutcome.Answered,
            "something_new" to DeliveredOutcome.Ringing
        )) {
            api.deliveredResults.clear()
            api.deliveredResults += ApiResult.Success(DeliveredResponse(state))
            assertEquals(state, expected, outbox.deliver(PAGE))
        }
        assertTrue(rows().isEmpty())
        assertEquals(0, scheduled)
    }

    @Test
    fun deliveredDropsOnFinalErrors() = runTest {
        val outbox = outbox()
        api.deliveredResults += ApiResult.Final(410, "page_ended")
        assertEquals(DeliveredOutcome.Drop(410, "page_ended"), outbox.deliver(PAGE))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun deliveredOnNetworkFailureAlertsAnywayAndQueuesARetry() = runTest {
        api.deliveredResults += ApiResult.Retryable(null, cause = IOException("offline"))
        api.deliveredResults += ApiResult.Success(DeliveredResponse("ringing"))
        val outbox = outbox()
        assertEquals(DeliveredOutcome.Unreachable, outbox.deliver(PAGE))
        runCurrent()
        val row = rows().single()
        assertEquals(OutboxKind.DELIVERED, row.kind)
        assertEquals(PAGE, row.pageId)
        assertEquals(1, scheduled)
        assertEquals("the retry waits for its backoff", 1, api.calls.size)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, api.calls.size)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun deliveredOnRateLimitHonoursRetryAfterForTheQueuedRetry() = runTest {
        api.deliveredResults += ApiResult.Retryable(429, retryAfterMs = 20_000)
        val outbox = outbox()
        assertEquals(DeliveredOutcome.Unreachable, outbox.deliver(PAGE))
        assertEquals(20_000L, rows().single().nextAttemptAt)
    }

    // ---- send window (ADR 0007) ----

    private fun TestScope.collect(outbox: Outbox): MutableList<OutboxOutcome> {
        val events = mutableListOf<OutboxOutcome>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { outbox.outcomes.toList(events) }
        return events
    }

    @Test
    fun unsentPageExpiresWithoutACancel() = runTest {
        api.pageResults += ApiResult.NotEnrolled(NotEnrolledReason.NOT_ENROLLED)
        val outbox = outbox()
        val events = collect(outbox)
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        assertEquals(listOf("page:$PAGE"), api.calls)
        assertEquals(0, rows().single().attempts)

        advanceTimeBy(Outbox.PAGE_SEND_WINDOW_MS - 1)
        outbox.resume()
        runCurrent()
        assertEquals("still inside the window", 1, rows().size)

        advanceTimeBy(1)
        outbox.resume()
        runCurrent()
        assertTrue("page never reached the server: no cancel row", rows().isEmpty())
        assertEquals(listOf("page:$PAGE", "page:$PAGE"), api.calls)
        assertEquals(listOf<OutboxOutcome.Result>(OutboxOutcome.Result.Expired), events.map { it.result })
    }

    @Test
    fun attemptedPageExpiresAndIsCancelledOnTheServer() = runTest {
        api.pageResults += ApiResult.Retryable(503)
        val outbox = outbox()
        val events = collect(outbox)
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        advanceTimeBy(Outbox.PAGE_SEND_WINDOW_MS + 1_000)
        runCurrent()
        assertTrue(rows().isEmpty())
        assertEquals("cancel:$PAGE", api.calls.last())
        assertEquals(1, api.calls.count { it.startsWith("cancel") })
        assertTrue("the page was retried until the window closed", api.pageRequests.size > 5)
        assertEquals(
            listOf<OutboxOutcome.Result>(OutboxOutcome.Result.Expired, OutboxOutcome.Result.Sent(null)),
            events.filter { it.kind == OutboxKind.PAGE || it.kind == OutboxKind.CANCEL }.map { it.result }
        )
    }

    @Test
    fun cancelBeforeAnyAttemptCollapsesTheUnsentPage() = runTest {
        val outbox = outbox()
        val events = collect(outbox)
        outbox.enqueue(OutboxAction.Page(page()))
        outbox.enqueue(OutboxAction.Cancel(PAGE)) // the loop has not run yet
        runCurrent()
        assertTrue(api.calls.isEmpty())
        assertTrue(rows().isEmpty())
        assertEquals(listOf<OutboxOutcome.Result>(OutboxOutcome.Result.CancelledLocally), events.map { it.result })
    }

    @Test
    fun cancelAfterAnAttemptIsQueuedBehindThePage() = runTest {
        api.pageResults += ApiResult.Retryable(503)
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        outbox.enqueue(OutboxAction.Cancel(PAGE))
        assertEquals(listOf(OutboxKind.PAGE, OutboxKind.CANCEL), rows().map { it.kind })
    }

    @Test
    fun otherKindsAreCappedAtTwentyFourHours() = runTest {
        api.actionResults += ApiResult.NotEnrolled(NotEnrolledReason.NEEDS_REENROLLMENT)
        val outbox = outbox()
        val events = collect(outbox)
        outbox.enqueue(OutboxAction.Acknowledge(PAGE))
        runCurrent()
        advanceTimeBy(Outbox.MAX_ROW_AGE_MS - 1)
        outbox.resume()
        runCurrent()
        assertEquals(1, rows().size)
        advanceTimeBy(1)
        outbox.resume()
        runCurrent()
        assertTrue(rows().isEmpty())
        assertEquals(listOf<OutboxOutcome.Result>(OutboxOutcome.Result.Expired), events.map { it.result })
    }

    // ---- token unavailable, final-error handling, caps, loop robustness ----

    @Test
    fun deliveredWithUnreadableTokenAlertsAndQueuesARetry() = runTest {
        api.deliveredResults += ApiResult.NotEnrolled(NotEnrolledReason.TOKEN_UNAVAILABLE)
        val outbox = outbox()
        assertEquals(DeliveredOutcome.Unreachable, outbox.deliver(PAGE))
        assertEquals(OutboxKind.DELIVERED, rows().single().kind)
        assertEquals(1, scheduled)
    }

    @Test
    fun deliveredDropsWhenNotEnrolled() = runTest {
        val outbox = outbox()
        for (reason in listOf(NotEnrolledReason.NOT_ENROLLED, NotEnrolledReason.NEEDS_REENROLLMENT)) {
            api.deliveredResults.clear()
            api.deliveredResults += ApiResult.NotEnrolled(reason)
            assertTrue(outbox.deliver(PAGE) is DeliveredOutcome.Drop)
        }
        assertTrue(rows().isEmpty())
    }

    @Test
    fun anUnreadableTokenIsRetriedWithBackoffNotBlocked() = runTest {
        api.actionResults += ApiResult.NotEnrolled(NotEnrolledReason.TOKEN_UNAVAILABLE)
        api.actionResults += ApiResult.Success(Unit)
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Acknowledge(PAGE))
        runCurrent()
        assertEquals(1, rows().single().attempts)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, api.calls.size)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun deliveredDropsOnlyForEndedNotOursOrUnauthorisedAndAlertsOtherwise() = runTest {
        val outbox = outbox()
        for (status in listOf(401, 403, 404, 410)) {
            api.deliveredResults.clear()
            api.deliveredResults += ApiResult.Final(status, "x")
            assertEquals(status.toString(), DeliveredOutcome.Drop(status, "x"), outbox.deliver(PAGE))
        }
        val alerts = listOf(
            ApiResult.Final(200, "bad_response"),
            ApiResult.Final(302, null),
            ApiResult.Final(400, "invalid_request"),
            ApiResult.Final(418, null)
        )
        for (final in alerts) {
            api.deliveredResults.clear()
            api.deliveredResults += final
            assertEquals(final.toString(), DeliveredOutcome.Ringing, outbox.deliver(PAGE))
        }
        assertTrue(rows().isEmpty())
    }

    @Test
    fun aFinal2xxMeansTheServerAcceptedIt() = runTest {
        api.actionResults += ApiResult.Final(200, "bad_response")
        val outbox = outbox()
        val events = collect(outbox)
        outbox.enqueue(OutboxAction.Answer(PAGE))
        runCurrent()
        assertTrue(rows().isEmpty())
        assertEquals(listOf<OutboxOutcome.Result>(OutboxOutcome.Result.Sent(null)), events.map { it.result })
    }

    @Test
    fun retryAfterIsCappedAtFiveMinutes() = runTest {
        api.pageResults += ApiResult.Retryable(429, retryAfterMs = 3_600_000)
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        assertEquals(Outbox.RETRY_AFTER_CAP_MS, rows().single().nextAttemptAt)
    }

    @Test
    fun aDueCancelBehindARetryingPageDoesNotSpinTheLoop() = runTest {
        api.pageResults += ApiResult.Retryable(503)
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent()
        outbox.enqueue(OutboxAction.Cancel(PAGE)) // due now, but must wait for the page
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("page:$PAGE"), api.calls)
    }

    @Test
    fun anUnexpectedFailureDoesNotWedgeTheLoop() = runTest {
        api.failNext = IllegalStateException("database exploded")
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Cancel(PAGE))
        runCurrent()
        assertTrue(api.calls.isEmpty())
        assertEquals(1, rows().size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("cancel:$PAGE"), api.calls)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun resumeSchedulesTheWorkerAndKicksTheLoop() = runTest {
        db.outboxDao().insert(OutboxEntity("r1", OutboxKind.CANCEL, PAGE, "", 0, 0, 0))
        val outbox = outbox()
        outbox.resume()
        runCurrent()
        assertEquals(1, scheduled)
        assertEquals(listOf("cancel:$PAGE"), api.calls)
    }

    // ---- attempts are recorded before sending; cancel is durable and never waits ----

    @Test
    fun anAttemptIsRecordedBeforeTheRequestSoProcessDeathMidRequestIsRemembered() = runTest {
        api.failNext = CancellationException("process died mid-request")
        val first = outbox()
        first.enqueue(OutboxAction.Page(page()))
        runCurrent()
        assertTrue(api.calls.isEmpty())
        assertEquals("the request may have reached the server", 1, rows().single().attempts)

        // New process: a user cancel must be sent, not collapsed locally.
        val second = outbox()
        second.enqueue(OutboxAction.Cancel(PAGE))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("page:$PAGE", "cancel:$PAGE"), api.calls)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun anExpiredPageThatWasInFlightAtProcessDeathIsCancelled() = runTest {
        api.failNext = CancellationException("process died mid-request")
        outbox().enqueue(OutboxAction.Page(page()))
        runCurrent()
        api.pageResults += ApiResult.Retryable(503)
        val second = outbox()
        second.resume()
        advanceTimeBy(Outbox.PAGE_SEND_WINDOW_MS + 1_000)
        runCurrent()
        assertEquals("cancel:$PAGE", api.calls.last())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun enqueueingACancelDoesNotWaitForADrainInProgress() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.gate = gate
        val outbox = outbox()
        outbox.enqueue(OutboxAction.Page(page()))
        runCurrent() // the drain is now inside createPage, holding its lock
        outbox.enqueue(OutboxAction.Cancel(PAGE)) // would hang here if it waited for the lock
        assertEquals(listOf(OutboxKind.PAGE, OutboxKind.CANCEL), rows().map { it.kind })
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("page:$PAGE", "cancel:$PAGE"), api.calls)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun aCancelSurvivesProcessDeathBeforeAnyDrainAndCollapsesInTheNextOne() = runTest {
        val deadScope = CoroutineScope(Job().apply { cancel() })
        val dying = outbox(scope = deadScope)
        dying.enqueue(OutboxAction.Page(page()))
        dying.enqueue(OutboxAction.Cancel(PAGE))
        assertEquals(listOf(OutboxKind.PAGE, OutboxKind.CANCEL), rows().map { it.kind })

        val next = outbox()
        val events = collect(next)
        next.drain()
        assertTrue(rows().isEmpty())
        assertTrue(api.calls.isEmpty())
        assertEquals(listOf<OutboxOutcome.Result>(OutboxOutcome.Result.CancelledLocally), events.map { it.result })
    }

    @Test
    fun expiryLeavesTheDevicesOwnReceiverRowsAlone() = runTest {
        api.pageResults += ApiResult.Retryable(503)
        api.deliveredResults += ApiResult.Retryable(503)
        val outbox = outbox()
        val events = collect(outbox)
        outbox.enqueue(OutboxAction.Page(page()))
        outbox.enqueue(OutboxAction.Delivered(PAGE)) // a Test alert: the page is to this phone itself
        runCurrent()
        advanceTimeBy(Outbox.PAGE_SEND_WINDOW_MS + 1_000)
        runCurrent()
        // The receiver row survives; the fresh cancel queues behind it (per-page order).
        assertEquals(setOf(OutboxKind.DELIVERED, OutboxKind.CANCEL), rows().map { it.kind }.toSet())
        assertEquals(
            listOf(OutboxKind.PAGE),
            events.filter { it.result == OutboxOutcome.Result.Expired }.map { it.kind }
        )
    }

    private companion object {
        const val PAGE = "5f0c2a9e-3b1d-4c7a-9e21-6d8b0f4a7c13"
    }
}
