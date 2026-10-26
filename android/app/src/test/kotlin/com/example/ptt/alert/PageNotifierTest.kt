package com.example.ptt.alert

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import com.example.ptt.talk.TalkActivity
import com.example.ptt.talk.TalkIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val PAGE = "66666666-6666-4666-8666-666666666666"

@RunWith(RobolectricTestRunner::class)
class PageNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private lateinit var notifier: PageNotifier

    private val normal = IncomingPage(PAGE, "normal", "user", "Mom")
    private val urgent = IncomingPage(PAGE, "urgent", "user", "Mom")

    @Before
    fun setUp() {
        AlertChannels.create(app)
        notifier = PageNotifier(app)
    }

    private fun posted(): Notification = shadowOf(manager).getNotification(PAGE, PageNotifier.PAGE_NOTIFICATION_ID)

    private fun Notification.title() = extras.getString(Notification.EXTRA_TITLE)

    private fun Notification.text() = extras.getString(Notification.EXTRA_TEXT)

    // --- channels ---

    @Test
    fun theFourChannelsExistWithTheirIds() {
        val ids = manager.notificationChannels.map { it.id }.toSet()
        assertEquals(setOf("page_normal", "page_urgent", "page_status", "page_quiet", "alert_service"), ids)
        // Creating them again (every app start, and the service) changes nothing.
        AlertChannels.create(app)
        assertEquals(5, manager.notificationChannels.size)
    }

    @Test
    fun normalChannelIsHighImportanceOnTheNotificationStreamAndPublicOnTheLockScreen() {
        val channel = manager.getNotificationChannel(AlertChannels.PAGE_NORMAL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertFalse(channel.canBypassDnd())
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
        assertNotNull(channel.sound)
        assertTrue(channel.shouldVibrate())
        assertEquals(AlertChannels.NORMAL_VIBRATION.toList(), channel.vibrationPattern.toList())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
    }

    @Test
    fun urgentChannelBypassesDoNotDisturbWithAlarmAttributesAndALongVibration() {
        val channel = manager.getNotificationChannel(AlertChannels.PAGE_URGENT)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.canBypassDnd())
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        // The looping tone belongs to AlertService; a channel sound would play on top of it.
        assertNull(channel.sound)
        assertTrue(channel.shouldVibrate())
        assertEquals(AlertChannels.URGENT_VIBRATION.toList(), channel.vibrationPattern.toList())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
        val short = AlertChannels.NORMAL_VIBRATION.sum()
        assertTrue(AlertChannels.URGENT_VIBRATION.sum() > 3 * short)
    }

    @Test
    fun statusChannelIsDefaultImportanceAndQuietChannelIsSilent() {
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(AlertChannels.STATUS).importance
        )
        val quiet = manager.getNotificationChannel(AlertChannels.QUIET)
        assertEquals(NotificationManager.IMPORTANCE_LOW, quiet.importance)
        assertNull(quiet.sound)
        assertFalse(quiet.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PUBLIC, quiet.lockscreenVisibility)
    }

    // --- incoming-page notifications ---

    @Test
    fun aNormalPageShowsTheCallerAndLevelAndAnswersOnTap() {
        notifier.incoming(normal, alerting = true)
        val n = posted()
        assertEquals(AlertChannels.PAGE_NORMAL, n.channelId)
        assertEquals("Mom is paging you", n.title())
        assertEquals("Page. Tap to answer.", n.text())
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertNull(n.fullScreenIntent)
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(TalkActivity::class.java.name, tap.component?.className)
        assertEquals(PAGE, tap.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
        assertEquals(TalkIntents.MODE_ANSWER, tap.getStringExtra(TalkIntents.EXTRA_MODE))
        assertEquals("Mom", tap.getStringExtra(TalkIntents.EXTRA_CALLER_NAME))
    }

    @Test
    fun theAcknowledgedActionTargetsTheReceiverWithThePageId() {
        notifier.incoming(normal, alerting = true)
        val action = posted().actions.single()
        assertEquals("Acknowledged", action.title.toString())
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(AlertActionReceiver::class.java.name, intent.component?.className)
        assertEquals(AlertActionReceiver.ACTION_ACKNOWLEDGE, intent.action)
        assertEquals(PAGE, intent.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
        assertTrue(shadowOf(action.actionIntent).isBroadcast)
    }

    @Test
    fun anUrgentPageHasAFullScreenIntentToTheIncomingScreenAndATapThatAnswers() {
        notifier.incoming(urgent, alerting = true)
        val n = posted()
        assertEquals(AlertChannels.PAGE_URGENT, n.channelId)
        assertEquals("Mom: urgent page", n.title())
        assertEquals("Urgent page. Tap to answer.", n.text())
        val fullScreen = shadowOf(n.fullScreenIntent).savedIntent
        assertEquals(TalkActivity::class.java.name, fullScreen.component?.className)
        assertEquals(TalkIntents.MODE_INCOMING, fullScreen.getStringExtra(TalkIntents.EXTRA_MODE))
        assertEquals(PAGE, fullScreen.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(TalkIntents.MODE_ANSWER, tap.getStringExtra(TalkIntents.EXTRA_MODE))
        assertEquals(1, n.actions.size)
    }

    @Test
    fun aQuietNotificationIsSilentStillAnswerableAndHasNoAcknowledgeAction() {
        notifier.incoming(urgent, alerting = false, acknowledgedElsewhere = true)
        val n = posted()
        assertEquals(AlertChannels.QUIET, n.channelId)
        assertEquals("Acknowledged on another phone. Tap to answer.", n.text())
        assertNull(n.fullScreenIntent)
        assertNull(n.actions)
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(TalkIntents.MODE_ANSWER, tap.getStringExtra(TalkIntents.EXTRA_MODE))
        // Acknowledging here replaces the same notification with the "here" wording.
        notifier.acknowledged(normal)
        assertEquals("Acknowledged. Tap to answer.", posted().text())
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun anUnnamedCallerIsSomeone() {
        notifier.incoming(IncomingPage(PAGE, "normal", "user", null), alerting = true)
        assertEquals("Someone is paging you", posted().title())
    }

    // --- replacement notifications ---

    @Test
    fun cancelledAndAnsweredElsewhereReplaceThePageNotificationAndDismissThemselves() {
        notifier.incoming(normal, alerting = true)
        notifier.cancelled(PAGE)
        var n = posted()
        assertEquals("Page cancelled", n.title())
        assertEquals(AlertChannels.STATUS, n.channelId)
        assertEquals(PageNotifier.SHORT_LIVED_MS, n.timeoutAfter)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        notifier.answeredElsewhere(PAGE)
        n = posted()
        assertEquals("Answered on another phone", n.title())
        assertEquals(PageNotifier.SHORT_LIVED_MS, n.timeoutAfter)
    }

    @Test
    fun missedLeavesAPersistentNotificationNamingTheCaller() {
        notifier.incoming(normal, alerting = true)
        notifier.missed(PAGE, "Mom")
        val n = posted()
        assertEquals("Missed page from Mom", n.title())
        assertEquals(0L, n.timeoutAfter)
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        notifier.missed(PAGE, null)
        assertEquals("Missed page", posted().title())
    }

    @Test
    fun theSenderSeesWhoAnsweredAndATapOpensTheTalkScreen() {
        notifier.senderAnswered(PAGE, "Dad")
        val n = posted()
        assertEquals("Dad answered", n.title())
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(TalkActivity::class.java.name, tap.component?.className)
        assertEquals(TalkIntents.MODE_OUTGOING, tap.getStringExtra(TalkIntents.EXTRA_MODE))
    }

    @Test
    fun failedAndMissedSenderStatesPostAShortNotification() {
        notifier.senderState(PAGE, "failed", "Dad")
        assertEquals("Page to Dad failed", posted().title())
        notifier.senderState(PAGE, "missed", "Dad")
        assertEquals("Dad did not answer your page", posted().title())
    }

    @Test
    fun answeredByThisDeviceShowsAnAnsweredNotificationThatOpensTheTalkScreen() {
        notifier.answered(normal)
        val n = posted()
        assertEquals("Answered", n.title())
        assertEquals(AlertChannels.STATUS, n.channelId)
        assertEquals(PAGE, shadowOf(n.contentIntent).savedIntent.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
    }

    @Test
    fun testPushAndClear() {
        notifier.test()
        assertEquals(
            "Test push",
            shadowOf(manager).getNotification(PageNotifier.TEST_TAG, PageNotifier.TEST_NOTIFICATION_ID).title()
        )
        notifier.incoming(normal, alerting = true)
        notifier.clear(PAGE)
        assertNull(shadowOf(manager).getNotification(PAGE, PageNotifier.PAGE_NOTIFICATION_ID))
    }

    @Test
    fun anUnknownPageAcknowledgedElsewhereStillGetsAVisibleNotification() {
        notifier.acknowledgedUnknown(PAGE)
        assertEquals("Page acknowledged on another phone", posted().title())
    }

    @Test
    fun aRingingNotificationIsOngoingAndComesBackIfSwipedAway() {
        notifier.incoming(urgent, alerting = true)
        val n = posted()
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val delete = shadowOf(n.deleteIntent)
        assertTrue(delete.isBroadcast)
        assertEquals(AlertActionReceiver.ACTION_REPOST, delete.savedIntent.action)
        assertEquals(PAGE, delete.savedIntent.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
        // Acknowledged: no longer ongoing, nothing to put back.
        notifier.acknowledged(urgent)
        assertEquals(0, posted().flags and Notification.FLAG_ONGOING_EVENT)
        assertNull(posted().deleteIntent)
    }

    @Test
    fun anUpdateOfAVisibleNotificationMakesNoSoundOfItsOwn() {
        // Only an update of a notification that is still showing is silent; one the user swiped away and
        // that is posted anew alerts again, which is acceptable while the page rings.
        notifier.incoming(normal, alerting = true, renotify = false)
        assertTrue(posted().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        notifier.incoming(normal, alerting = true)
        assertEquals(0, posted().flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun intentsOfDifferentPagesAndModesAreToldApartByTheirData() {
        notifier.incoming(urgent, alerting = true)
        val n = posted()
        val tap = shadowOf(n.contentIntent).savedIntent
        val full = shadowOf(n.fullScreenIntent).savedIntent
        assertEquals(TalkIntents.identity(PAGE, "answer"), tap.data)
        assertEquals(TalkIntents.identity(PAGE, "incoming"), full.data)
        assertEquals(TalkIntents.identity("other", "answer"), TalkIntents.intent(app, "other", "answer", null).data)
        val ack = shadowOf(n.actions.single().actionIntent).savedIntent
        assertEquals(TalkIntents.identity(PAGE, "ACKNOWLEDGE"), ack.data)
    }

    @Test
    fun anUnknownPageNotificationIsInformationalOnly() {
        notifier.acknowledgedUnknown(PAGE)
        assertNull(posted().contentIntent)
    }

    @Test
    fun anUrgentPostRecreatesTheChannelSoBypassDndCanBeApplied() {
        manager.deleteNotificationChannel(AlertChannels.PAGE_URGENT)
        assertNull(manager.getNotificationChannel(AlertChannels.PAGE_URGENT))
        notifier.incoming(urgent, alerting = true)
        assertTrue(manager.getNotificationChannel(AlertChannels.PAGE_URGENT).canBypassDnd())
    }

    @Test
    fun theForegroundNoticeIsOnItsOwnChannelAndOpensTheHighestRingingPage() {
        val n = AlertService.notification(app, urgent)
        assertEquals(AlertChannels.SERVICE, n.channelId)
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals(TalkIntents.MODE_INCOMING, tap.getStringExtra(TalkIntents.EXTRA_MODE))
        assertEquals(PAGE, tap.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
        val normalTap = shadowOf(AlertService.notification(app, normal).contentIntent).savedIntent
        assertEquals(TalkIntents.MODE_ANSWER, normalTap.getStringExtra(TalkIntents.EXTRA_MODE))
        assertNull(AlertService.notification(app, null).contentIntent)
    }

    @Test
    fun leftoverOngoingPageNotificationsAreDroppedAndOthersKept() {
        val other = IncomingPage("other-page", "normal", "user", "Dad")
        notifier.incoming(normal, alerting = true)
        notifier.incoming(other, alerting = true)
        notifier.acknowledged(other)
        notifier.test()
        notifier.dropLeftovers()
        assertNull(shadowOf(manager).getNotification(PAGE, PageNotifier.PAGE_NOTIFICATION_ID))
        assertNotNull(shadowOf(manager).getNotification("other-page", PageNotifier.PAGE_NOTIFICATION_ID))
        assertNotNull(shadowOf(manager).getNotification(PageNotifier.TEST_TAG, PageNotifier.TEST_NOTIFICATION_ID))
    }
}
