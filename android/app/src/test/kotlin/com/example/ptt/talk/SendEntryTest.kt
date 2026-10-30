package com.example.ptt.talk

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.ptt.R
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PttDatabase
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.setup.ADMIN_USER
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val TEST_TIMEOUT_MS = 60_000L
private val PAT_NORMAL = SendRequest(ADMIN_USER, EnvelopePayload.LEVEL_NORMAL, "Pat")
private val PAT_URGENT = SendRequest(ADMIN_USER, EnvelopePayload.LEVEL_URGENT, "Pat")
private val LEE_NORMAL = SendRequest(LEE_USER, EnvelopePayload.LEVEL_NORMAL, "Lee")

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SendEntryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val compose = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var h: SenderHarness
    private lateinit var sender: PageSender
    private val db: PttDatabase by lazy {
        Room.databaseBuilder(app, PttDatabase::class.java, PttDatabase.FILE_NAME).allowMainThreadQueries().build()
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        h = SenderHarness(tmp.newFolder())
    }

    @After
    fun tearDown() {
        SendActivity.resetKeyguard()
        db.close()
        appScope.cancel()
        h.close()
    }

    private fun viewModel(sender: PageSender) = SendViewModel(sender, h.f.activePage, app, appScope)

    private suspend fun pageRows() = h.f.db.outboxDao().all().filter { it.kind == OutboxKind.PAGE }

    private fun poll(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun send(vm: SendViewModel, request: SendRequest) {
        assertTrue(vm.accept(request))
        vm.run()
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun theSendActivityIsNotExportedAndNeverShownOverTheLockScreen() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, SendActivity::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        // Never over the lock screen.
        val declaration = java.io.File("src/main/AndroidManifest.xml").readText()
            .substringAfter("android:name=\".talk.SendActivity\"").substringBefore("/>")
        assertFalse(declaration.contains("showWhenLocked"))
        val request = SendIntents.request(SendIntents.intent(app, ADMIN_USER, "urgent", "Pat"))
        assertEquals(PAT_URGENT, request)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aSendOpensThePageOnceAndRepeatsAreIgnored() = runTest {
        val vm = with(h) { rig().sender }.also { sender = it }.let(::viewModel)
        val events = CopyOnWriteArrayList<SendEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        send(vm, PAT_NORMAL)
        // A second tap, or the same intent redelivered, while the first is being stored.
        assertFalse(vm.accept(PAT_NORMAL))
        poll { events.isNotEmpty() }
        assertFalse(vm.accept(PAT_NORMAL))
        assertFalse(vm.accept(LEE_NORMAL))
        vm.run()
        vm.confirmEnd()
        Thread.sleep(200)
        val open = events.single() as SendEvent.Open
        assertEquals("Pat", open.name)
        val rows = pageRows()
        assertEquals(1, rows.size)
        assertEquals(open.pageId, rows.single().pageId)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun theSamePersonAtTheSameLevelJustShowsThatPage() = runTest {
        val vm = with(h) { rig().sender }.also { sender = it }.let(::viewModel)
        val events = CopyOnWriteArrayList<SendEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        send(vm, PAT_NORMAL)
        poll { events.size == 1 }
        val second = viewModel(sender)
        backgroundScope.launch(Dispatchers.Unconfined) { second.events.toList(events) }
        send(second, PAT_NORMAL)
        poll { events.size == 2 }
        assertEquals((events[0] as SendEvent.Open).pageId, (events[1] as SendEvent.Open).pageId)
        assertEquals(1, pageRows().size)
        assertTrue(second.ui.value is SendUi.Working)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun anotherLevelOrPersonAsksThenEndsAndSends() = runTest {
        val vm = with(h) { rig().sender }.also { sender = it }.let(::viewModel)
        val events = CopyOnWriteArrayList<SendEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        send(vm, PAT_NORMAL)
        poll { events.size == 1 }
        val urgent = viewModel(sender)
        backgroundScope.launch(Dispatchers.Unconfined) { urgent.events.toList(events) }
        send(urgent, PAT_URGENT)
        poll { urgent.ui.value is SendUi.Confirm }
        assertEquals(SendUi.Confirm("Pat"), urgent.ui.value)
        assertEquals(1, pageRows().size)
        // Run is not a way past the question.
        urgent.run()
        urgent.confirmEnd()
        poll { events.size == 2 }
        assertEquals("urgent", h.f.activePage.current()?.level)
        assertEquals(PAT_URGENT.userId, h.f.activePage.current()?.peerUserId)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun cancellingTheQuestionSendsNothingAndClosesLeavingThePage() = runTest {
        val first = with(h) { rig().sender }.also { sender = it }.let(::viewModel)
        val events = CopyOnWriteArrayList<SendEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { first.events.toList(events) }
        send(first, PAT_NORMAL)
        poll { events.size == 1 }
        val before = h.f.activePage.current()
        val vm = viewModel(sender)
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        send(vm, LEE_NORMAL)
        poll { vm.ui.value is SendUi.Confirm }
        vm.cancel()
        poll { events.size == 2 }
        assertEquals(SendEvent.Close, events[1])
        assertEquals(before, h.f.activePage.current())
        assertEquals(1, pageRows().size)
        assertFalse(vm.accept(LEE_NORMAL))
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun eachFailureShowsItsMessage() = runTest {
        val vm = with(h) { viewModel(rig().sender) }
        send(vm, SendRequest("99999999-9999-4999-8999-999999999999", "normal", "Ghost"))
        poll { vm.ui.value is SendUi.Failed }
        assertEquals(SendUi.Failed(R.string.family_no_phones, "Ghost"), vm.ui.value)
        assertTrue(pageRows().isEmpty())
        // Not enrolled / no roster.
        h.f.rosters.clear()
        send(vm, PAT_NORMAL)
        poll { vm.ui.value == SendUi.Failed(R.string.send_not_ready, null) }
        assertTrue(pageRows().isEmpty())
        assertEquals(R.string.send_keys_unavailable, SendResult.KeysUnavailable.failureMessage())
        assertEquals(R.string.send_error, SendResult.Error.failureMessage())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun nothingIsSentUntilTheUnlockAnswersAndOnlyTheLatestPromptCounts() = runTest {
        val vm = with(h) { viewModel(rig().sender) }
        val events = CopyOnWriteArrayList<SendEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        assertTrue(vm.accept(PAT_NORMAL))
        val first = vm.newUnlock()
        // A newer request while the first prompt is up: the system cancels the first prompt.
        assertTrue(vm.accept(PAT_URGENT))
        val second = vm.newUnlock()
        vm.unlocked(first, false)
        Thread.sleep(100)
        assertTrue(events.isEmpty())
        assertTrue(pageRows().isEmpty())
        assertEquals(SendUi.Unlocking, vm.ui.value)
        vm.unlocked(second, true)
        poll { events.size == 1 }
        assertEquals("urgent", Json.decodeFromString(CreatePageRequest.serializer(), pageRows().single().body).level)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aFailedUnlockClosesWithoutSending() = runTest {
        val vm = with(h) { viewModel(rig().sender) }
        val events = CopyOnWriteArrayList<SendEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        assertTrue(vm.accept(PAT_NORMAL))
        vm.unlocked(vm.newUnlock(), false)
        assertEquals(listOf<SendEvent>(SendEvent.Close), events.toList())
        vm.run()
        assertTrue(pageRows().isEmpty())
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun anIntentWithoutARequestFinishes() {
        ActivityScenario.launch<SendActivity>(Intent(app, SendActivity::class.java)).use { scenario ->
            awaitTrue("finish") { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: ComposeTimeoutException) {
            throw IllegalStateException("timed out waiting for $what", e)
        }
    }
}
