package com.example.ptt.talk

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.FakeDevices
import com.example.ptt.alert.FakeNotices
import com.example.ptt.alert.FakeServiceControl
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.TestDb
import com.example.ptt.outbox.Backoff
import com.example.ptt.outbox.FakePttApi
import com.example.ptt.outbox.Outbox
import com.example.ptt.push.PageEvent
import com.example.ptt.push.SessionKeys
import java.time.Clock
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val PAGE = "11111111-1111-4111-8111-111111111111"
private const val OTHER = "22222222-2222-4222-8222-222222222222"
private const val PEER = "33333333-3333-4333-8333-333333333333"

@RunWith(RobolectricTestRunner::class)
class TalkActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** A second connection to the app's real database, to look at the outbox rows (the server is unreachable). */
    private val db: PttDatabase by lazy {
        Room.databaseBuilder(app, PttDatabase::class.java, PttDatabase.FILE_NAME).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        TalkActivity.viewModelFactory = null
        fakeScope.cancel()
        fakeDb?.close()
        db.close()
    }

    private val fakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fakeDb: PttDatabase? = null

    /**
     * The sender's screen of an answered page whose live session is up, with a view model built on fakes: [audio]
     * records what reaches the microphone's hold.
     */
    private fun liveSendersScreen(
        audio: FakeAudioSource,
        firstTalkerLocked: Boolean = false
    ): ActivityScenario<TalkActivity> {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val local = TestDb.inMemory(app).also { fakeDb = it }
        val clock = Clock.systemUTC()
        val repo = ActivePageRepository(local.activePageDao(), clock)
        val keys = SessionKeys()
        val notices = FakeNotices()
        val outbox = Outbox(local.outboxDao(), FakePttApi(), Json, clock, { }, fakeScope, Backoff(Random(1)))
        val engine = AlertEngine(fakeScope, FakeDevices().devices, notices, FakeServiceControl(), outbox, repo, keys)
        val talk = FakeTalk()
        talk.state.value = TalkSessionState(
            pageId = PAGE,
            stage = SessionStage.CONVERSATION,
            connection = TalkConnection.LIVE,
            peerPresent = true,
            firstTalkerLocked = firstTalkerLocked
        )
        val events = MutableSharedFlow<PageEvent>()
        runBlocking { repo.start(PAGE, PageRole.SENDER, "session-1", PEER, "normal", "answered") }
        TalkActivity.viewModelFactory = viewModelFactory {
            initializer {
                TalkViewModel(engine, repo, outbox, keys, notices, events, fakeScope, clock, talk, audio, FakeCues())
            }
        }
        return launch(PAGE, TalkIntents.MODE_OUTGOING, "Mom")
    }

    private fun ActivityScenario<TalkActivity>.holdTalk() {
        lateinit var vm: TalkViewModel
        onActivity { vm = it.viewModel }
        awaitTrue("talk to be enabled") { vm.live.value.talkEnabled }
        onActivity { assertTrue(it.viewModel.pressTalk()) }
    }

    private fun launch(pageId: String, mode: String, caller: String?) =
        ActivityScenario.launch<TalkActivity>(TalkIntents.intent(app, pageId, mode, caller))

    private fun answers(pageId: String) =
        runBlocking { db.outboxDao().all() }.count { it.kind == OutboxKind.ANSWER && it.pageId == pageId }

    /**
     * The outbox and the active-page row are written from the application scope, off the test thread, and the
     * screen follows on the main thread: wait with the compose rule, which keeps the main looper running.
     */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                // Messages posted to the main looper from other threads (Room's flow) run only when it is idled.
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: ComposeTimeoutException) {
            throw IllegalStateException("timed out waiting for $what", e)
        }
    }

    private fun shows(text: String) = awaitTrue("\"$text\"") {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theIntentContractCarriesPageIdModeAndCaller() {
        val intent = TalkIntents.intent(app, "page-1", TalkIntents.MODE_INCOMING, "Mom")
        assertEquals(TalkActivity::class.java.name, intent.component?.className)
        assertEquals("page-1", intent.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
        assertEquals(TalkIntents.MODE_INCOMING, intent.getStringExtra(TalkIntents.EXTRA_MODE))
        assertEquals("Mom", intent.getStringExtra(TalkIntents.EXTRA_CALLER_NAME))
        val unnamed = TalkIntents.intent(app, "p", TalkIntents.MODE_ANSWER, null)
        assertNull(unnamed.getStringExtra(TalkIntents.EXTRA_CALLER_NAME))
    }

    @Test
    fun theManifestKeepsTheTalkScreenInItsOwnLockScreenTask() {
        val info = app.packageManager.getActivityInfo(
            android.content.ComponentName(app, TalkActivity::class.java),
            0
        )
        assertEquals("com.example.ptt.talk", info.taskAffinity)
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertFalse(info.exported)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
        // The two lock-screen flags are not in ActivityInfo's public API: read them from the manifest source.
        val declaration = java.io.File("src/main/AndroidManifest.xml").readText()
            .substringAfter("android:name=\".talk.TalkActivity\"").substringBefore("/>")
        assertTrue(declaration.contains("android:showWhenLocked=\"true\""))
        assertTrue(declaration.contains("android:turnScreenOn=\"true\""))
    }

    @Test
    fun aNotificationTapAnswersAndShowsTheAnsweredScreen() {
        launch(PAGE, TalkIntents.MODE_ANSWER, "Mom").use { scenario ->
            shows("Answered")
            shows("Mom")
            shows("Hold to talk")
            shows("End")
            awaitTrue("the answer") { answers(PAGE) == 1 }
            // Recreating the activity (rotation) keeps the view model, so nothing is sent again.
            scenario.recreate()
            shows("Answered")
            // A second tap on the notification is a new intent for the same page.
            scenario.onActivity { it.onNewIntent(TalkIntents.intent(app, PAGE, TalkIntents.MODE_ANSWER, "Mom")) }
            compose.waitForIdle()
            assertEquals(1, answers(PAGE))
        }
    }

    @Test
    fun theIncomingScreenAnswersOnlyWhenTheButtonIsPressed() {
        launch(PAGE, TalkIntents.MODE_INCOMING, "Mom").use {
            shows("Urgent page")
            shows("Mom")
            compose.waitForIdle()
            assertEquals(0, answers(PAGE))
            compose.onNodeWithText("Answer").performClick()
            shows("Answered")
            awaitTrue("the answer") { answers(PAGE) == 1 }
        }
    }

    @Test
    fun aNewIntentForAnotherPageShowsThatPage() {
        launch(PAGE, TalkIntents.MODE_INCOMING, "Mom").use { scenario ->
            shows("Mom")
            scenario.onActivity { it.onNewIntent(TalkIntents.intent(app, OTHER, TalkIntents.MODE_INCOMING, "Dad")) }
            shows("Dad")
            assertEquals(0, answers(PAGE))
            // ActivityScenario finds its activity again by its launch intent when closing.
            scenario.onActivity { it.intent = TalkIntents.intent(app, PAGE, TalkIntents.MODE_INCOMING, "Mom") }
        }
    }

    @Test
    fun endQueuesACancelForgetsThePageAndClosesTheScreen() {
        launch(PAGE, TalkIntents.MODE_ANSWER, "Mom").use { scenario ->
            lateinit var activity: TalkActivity
            scenario.onActivity { activity = it }
            awaitTrue("the answer") { answers(PAGE) == 1 }
            compose.onNodeWithText("End").performClick()
            awaitTrue("the cancel") {
                runBlocking { db.outboxDao().all() }.count { it.kind == OutboxKind.CANCEL && it.pageId == PAGE } == 1
            }
            awaitTrue("the screen to close") { activity.isFinishing }
            assertNull(runBlocking { db.activePageDao().get() })
        }
    }

    @Test
    fun theRowGoingAwayShowsTheEndedLineAndClosesAfterTheDelay() {
        launch(PAGE, TalkIntents.MODE_INCOMING, "Mom").use { scenario ->
            lateinit var activity: TalkActivity
            scenario.onActivity { activity = it }
            val viewModel = activity.viewModel
            val repo = viewModel.activePage
            runBlocking { repo.start(PAGE, PageRole.RECEIVER, "session-1", PEER, "urgent", "ringing") }
            shows("Answer")
            runBlocking { repo.updateState(PAGE, "ended") }
            awaitTrue("the ended line") { viewModel.state.value.over }
            shows("Page ended")
            assertFalse(activity.isFinishing)
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(OVER_CLOSE_DELAY_MS + 100)
            compose.mainClock.autoAdvance = true
            awaitTrue("the screen to close") { activity.isFinishing }
        }
    }

    @Test
    fun aPageNobodyKnowsIsStillAnsweredWithoutACrash() {
        launch(OTHER, TalkIntents.MODE_ANSWER, null).use {
            shows("Answered")
            shows("Someone")
            awaitTrue("the answer") { answers(OTHER) == 1 }
        }
    }

    @Test
    fun theSendersScreenShowsTheNameTheLevelAndTheProgress() {
        runBlocking { db.activePageDao().replace(senderRow(PAGE, "urgent", "sending")) }
        launch(PAGE, TalkIntents.MODE_OUTGOING, "Mom").use { scenario ->
            lateinit var activity: TalkActivity
            scenario.onActivity { activity = it }
            shows("Urgent page to Mom")
            shows("Sending")
            shows("End")
            // Through the app's own repository: a second database connection would not notify its flows.
            runBlocking { activity.viewModel.activePage.updateState(PAGE, "acknowledged") }
            shows("Acknowledged")
        }
    }

    @Test
    fun endOnTheSendersScreenCancelsAndCloses() {
        runBlocking { db.activePageDao().replace(senderRow(PAGE, "normal", "delivered")) }
        launch(PAGE, TalkIntents.MODE_OUTGOING, null).use { scenario ->
            lateinit var activity: TalkActivity
            scenario.onActivity { activity = it }
            shows("Page to Someone")
            compose.onNodeWithText("End").performClick()
            awaitTrue("the screen to close") { activity.isFinishing }
            assertNull(runBlocking { db.activePageDao().get() })
        }
    }

    private fun senderRow(pageId: String, level: String, state: String, now: Long = System.currentTimeMillis()) =
        ActivePageEntity(pageId, PageRole.SENDER, "session-1", PEER, level, state, now, now)

    @Test
    fun aHoldIsReleasedWhenTheScreenStops() {
        val audio = FakeAudioSource(PAGE)
        liveSendersScreen(audio).use { scenario ->
            scenario.holdTalk()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(listOf("press", "release"), audio.calls)
        }
    }

    @Test
    fun aHoldIsReleasedWhenTheWindowLosesFocus() {
        val audio = FakeAudioSource(PAGE)
        liveSendersScreen(audio).use { scenario ->
            scenario.holdTalk()
            scenario.onActivity { it.onWindowFocusChanged(false) }
            assertEquals(listOf("press", "release"), audio.calls)
        }
    }

    @Test
    fun volumeDownIsHoldToTalkWhileTheConversationIsUp() {
        val audio = FakeAudioSource(PAGE)
        liveSendersScreen(audio).use { scenario ->
            lateinit var activity: TalkActivity
            scenario.onActivity { activity = it }
            awaitTrue("talk to be enabled") { activity.viewModel.live.value.talkEnabled }
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
            // The first key-down starts the hold, and it is consumed so the volume does not change.
            assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, down))
            assertEquals(listOf("press"), audio.calls)
            // The auto-repeats are consumed too, but do not make a second press.
            val repeat = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 2)
            assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeat))
            assertEquals(listOf("press"), audio.calls)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
            assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, up))
            assertEquals(listOf("press", "release"), audio.calls)
            // Volume-up is never ours.
            val volumeUp = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
            assertFalse(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, volumeUp))
            assertFalse(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, volumeUp))
            assertEquals(listOf("press", "release"), audio.calls)
        }
    }

    @Test
    fun volumeDownWhileFirstTalkerLockedIsConsumedAndDoesNothing() {
        val audio = FakeAudioSource(PAGE)
        liveSendersScreen(audio, firstTalkerLocked = true).use { scenario ->
            lateinit var activity: TalkActivity
            scenario.onActivity { activity = it }
            awaitTrue("the live screen to be active") { activity.viewModel.live.value.active }
            assertTrue(activity.viewModel.live.value.firstTalkerLocked)
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
            assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, down))
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
            assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, up))
            assertEquals(emptyList<String>(), audio.calls)
        }
    }

    @Test
    fun volumeKeyUpAfterFocusLossReleasesNothingTwice() {
        val audio = FakeAudioSource(PAGE)
        liveSendersScreen(audio).use { scenario ->
            lateinit var activity: TalkActivity
            scenario.onActivity { activity = it }
            awaitTrue("talk to be enabled") { activity.viewModel.live.value.talkEnabled }
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
            assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, down))
            activity.onWindowFocusChanged(false)
            assertEquals(listOf("press", "release"), audio.calls)
            activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))
            assertEquals(listOf("press", "release"), audio.calls)
        }
    }

    @Test
    fun volumeDownIsTheSystemsKeyOutsideTheConversation() {
        launch(PAGE, TalkIntents.MODE_INCOMING, "Mom").use { scenario ->
            lateinit var activity: TalkActivity
            scenario.onActivity { activity = it }
            compose.waitForIdle()
            assertFalse(activity.viewModel.live.value.active)
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
            assertFalse(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, down))
            assertFalse(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, down))
        }
    }

    @Test
    fun anIntentWithoutAPageIdFinishesTheScreen() {
        ActivityScenario.launch<TalkActivity>(Intent(app, TalkActivity::class.java)).use { scenario ->
            assertTrue(scenario.state == Lifecycle.State.DESTROYED)
        }
    }
}
