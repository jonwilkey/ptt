package com.example.ptt.alert

import android.app.Notification
import android.app.NotificationManager
import com.example.ptt.data.PageRole
import com.example.ptt.net.ApiResult
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.net.SignedObjectDto
import com.example.ptt.outbox.Backoff
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxAction
import com.example.ptt.outbox.VirtualClock
import com.example.ptt.setup.ISSUED_AT
import com.example.ptt.setup.USER
import com.example.ptt.talk.LEE_USER
import com.example.ptt.talk.SenderHarness
import com.example.ptt.talk.TalkIntents
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

private const val PAGE = "00000000-0000-4000-8000-000000000042"
private const val NOBODY = "99999999-9999-4999-8999-999999999999"
private const val OTHER = "00000000-0000-4000-8000-000000000043"

@RunWith(RobolectricTestRunner::class)
class SenderWaitingTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: SenderHarness
    private lateinit var scope: CoroutineScope
    private lateinit var outbox: Outbox
    private var offsetMs = 0L
    private val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        h = SenderHarness(tmp.newFolder())
        scope = CoroutineScope(Dispatchers.Default + Job())
        AlertChannels.create(app)
        val clock = VirtualClock { ISSUED_AT + offsetMs }
        outbox = Outbox(h.f.db.outboxDao(), h.api, Json, clock, { }, scope, Backoff(Random(1)))
        SenderWaiting(
            h.f.activePage,
            h.f.sync,
            h.f.tokens,
            outbox,
            h.keys,
            WaitingNotifier(app),
            h.notices,
            clock,
            scope
        ).start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        h.close()
    }

    private fun waiting(pageId: String = PAGE): Notification? =
        shadowOf(manager).getNotification(pageId, WaitingNotifier.WAITING_NOTIFICATION_ID)

    private fun eventually(check: () -> Boolean) = runBlocking {
        withTimeout(20_000) { while (!check()) kotlinx.coroutines.delay(10) }
    }

    private fun send(pageId: String = PAGE, state: String = "sending") = runBlocking {
        h.f.activePage.start(pageId, PageRole.SENDER, "s", LEE_USER, "normal", state)
    }

    private fun Notification.title() = extras.getString(Notification.EXTRA_TITLE)

    private fun Notification.text() = extras.getString(Notification.EXTRA_TEXT)

    @Test
    fun everyLiveStateShowsTheProgressLabelInOneOngoingSilentNotification() {
        send()
        eventually { waiting() != null }
        val first = waiting()!!
        assertEquals("Waiting for Lee", first.title())
        assertEquals("Sending", first.text())
        assertTrue(first.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(first.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(AlertChannels.QUIET, first.channelId)
        assertEquals(0, first.actions?.size ?: 0)
        for ((state, label) in listOf(
            "not_delivered" to "Not delivered yet",
            "delivered" to "Delivered",
            "acknowledged" to "Acknowledged"
        )) {
            runBlocking { h.f.activePage.updateState(PAGE, state) }
            eventually { waiting()?.text() == label }
        }
        // Updated in place: one notification for the page.
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun tappingReopensTheOutgoingTalkScreenForThePageWithTheName() {
        send()
        eventually { waiting() != null }
        val intent = shadowOf(waiting()!!.contentIntent).savedIntent
        assertEquals(PAGE, intent.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
        assertEquals(TalkIntents.MODE_OUTGOING, intent.getStringExtra(TalkIntents.EXTRA_MODE))
        assertEquals("Lee", intent.getStringExtra(TalkIntents.EXTRA_CALLER_NAME))
    }

    @Test
    fun removedWhenAnsweredEndedOrReplaced() {
        send()
        eventually { waiting() != null }
        runBlocking { h.f.activePage.updateState(PAGE, "answered") }
        eventually { waiting() == null }

        send(OTHER)
        eventually { waiting(OTHER) != null }
        runBlocking { h.f.activePage.end(OTHER) }
        eventually { waiting(OTHER) == null }

        send()
        eventually { waiting() != null }
        send(OTHER)
        eventually { waiting(OTHER) != null && waiting() == null }
    }

    @Test
    fun aPageThatFailsLocallyPostsTheFailedNoticeAndDropsTheWaitingOne() {
        send()
        eventually { waiting() != null }
        runBlocking { h.f.activePage.updateState(PAGE, "failed") }
        eventually { waiting() == null && h.notices.events.contains("sender-state:$PAGE:failed:Lee") }
        assertEquals(1, h.notices.count("sender-state"))
    }

    @Test
    fun aRowAlreadyFailedWhenObservationStartsDoesNotNotifyAgain() {
        send(state = "failed")
        Thread.sleep(300)
        assertNull(waiting())
        assertFalse(h.notices.events.any { it.startsWith("sender-state") })
    }

    @Test
    fun selfPagesAndReceiverRowsNeverShowOne() {
        runBlocking { h.f.activePage.start(PAGE, PageRole.SENDER, "s", USER, "normal", "sending") }
        runBlocking { h.f.activePage.start(OTHER, PageRole.RECEIVER, "s", LEE_USER, "normal", "sending") }
        Thread.sleep(300)
        assertNull(waiting())
        assertNull(waiting(OTHER))
    }

    @Test
    fun anUnknownPeerGetsTheGenericTitle() {
        runBlocking { h.f.activePage.start(PAGE, PageRole.SENDER, "s", NOBODY, "normal", "sending") }
        eventually { waiting() != null }
        assertEquals("Waiting for an answer", waiting()!!.title())
    }

    @Test
    fun aRowStillSendingPastTheSendWindowHasNoNotification() {
        offsetMs = Outbox.PAGE_SEND_WINDOW_MS + 1
        send()
        Thread.sleep(300)
        assertNull(waiting())
    }

    @Test
    fun aPageThePushEndedLeavesOnlyTheDispatchersNotice() {
        send()
        eventually { waiting() != null }
        runBlocking { h.f.activePage.end(PAGE) }
        eventually { waiting() == null }
        assertEquals(0, h.notices.count("sender-state"))
    }

    private fun request() = CreatePageRequest(PAGE, LEE_USER, "normal", "s", SignedObjectDto("p", "s", "g"))

    private fun failedAfterOutboxGaveUp() {
        eventually { waiting() == null && h.notices.events.contains("sender-state:$PAGE:failed:Lee") }
        assertEquals("failed", runBlocking { h.f.activePage.current()!!.state })
        assertEquals(1, h.notices.count("sender-state"))
    }

    @Test
    fun aPageTheServerRefusedForGoodFailsWithNoEngineBuilt() {
        send()
        eventually { waiting() != null }
        h.api.pageResults += ApiResult.Final(400, "bad_request")
        runBlocking { outbox.enqueue(OutboxAction.Page(request())) }
        failedAfterOutboxGaveUp()
    }

    @Test
    fun aPageTheOutboxGaveUpOnFailsWithNoEngineBuilt() {
        send()
        eventually { waiting() != null }
        h.api.pageResults += ApiResult.Retryable(503)
        runBlocking { outbox.enqueue(OutboxAction.Page(request())) }
        eventually { h.api.calls.isNotEmpty() }
        offsetMs = Outbox.PAGE_SEND_WINDOW_MS + 1
        runBlocking { outbox.drain() }
        failedAfterOutboxGaveUp()
    }

    @Test
    fun aLeftoverFromADeadProcessIsSweptAndTheLiveOneRestored() {
        WaitingNotifier(app).show(OTHER, "Lee", "sending")
        send()
        eventually { waiting() != null && waiting(OTHER) == null }
    }
}
