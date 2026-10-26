package com.example.ptt.alert

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.ptt.R
import com.example.ptt.talk.TalkIntents

/**
 * Posts every page notification. All notifications of one page share the tag (the page id) and id
 * [PAGE_NOTIFICATION_ID], so each state replaces the previous one: the incoming-page notification becomes
 * the quiet "acknowledged" one, then "Page cancelled" or "Missed page from ...".
 */
class PageNotifier(private val context: Context) : PageNotices {
    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    override fun incoming(page: IncomingPage, alerting: Boolean, acknowledgedElsewhere: Boolean, renotify: Boolean) {
        // Bypass-DND only sticks if the channel is (re)created while the app holds Do Not Disturb access.
        if (alerting && page.urgent) AlertChannels.create(context)
        val channel = when {
            !alerting -> AlertChannels.QUIET
            page.urgent -> AlertChannels.PAGE_URGENT
            else -> AlertChannels.PAGE_NORMAL
        }
        val text = when {
            acknowledgedElsewhere -> R.string.notif_acknowledged_elsewhere
            !alerting -> R.string.notif_acknowledged_here
            page.urgent -> R.string.notif_urgent_text
            else -> R.string.notif_normal_text
        }
        val builder = base(channel)
            .setContentTitle(incomingTitle(page))
            .setContentText(context.getString(text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!renotify)
            .setContentIntent(talk(page.pageId, TalkIntents.MODE_ANSWER, page.fromName))
        if (alerting) {
            // A ringing page must not be swipeable into a tone that nothing on screen can stop: it is ongoing,
            // and if the user removes it anyway (Android 14 allows that) it is posted again while still
            // ringing. That is a new post, so it alerts again; only an update of a visible one is silent.
            builder.setOngoing(true)
                .setDeleteIntent(action(page.pageId, AlertActionReceiver.ACTION_REPOST))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .addAction(
                    0,
                    context.getString(R.string.action_acknowledged),
                    action(page.pageId, AlertActionReceiver.ACTION_ACKNOWLEDGE)
                )
            if (page.urgent) {
                builder.setFullScreenIntent(talk(page.pageId, TalkIntents.MODE_INCOMING, page.fromName), true)
            }
        }
        notify(page.pageId, builder.build())
    }

    override fun acknowledged(page: IncomingPage) = incoming(page, alerting = false)

    private fun incomingTitle(page: IncomingPage): String {
        val name = page.fromName ?: context.getString(R.string.unknown_caller)
        return context.getString(if (page.urgent) R.string.notif_urgent_title else R.string.notif_normal_title, name)
    }

    override fun answered(page: IncomingPage) {
        val name = page.fromName ?: context.getString(R.string.unknown_caller)
        val notification = base(AlertChannels.STATUS)
            .setContentTitle(context.getString(R.string.notif_answered_title))
            .setContentText(context.getString(R.string.notif_answered_text, name))
            .setContentIntent(talk(page.pageId, TalkIntents.MODE_ANSWER, page.fromName))
            .build()
        notify(page.pageId, notification)
    }

    override fun cancelled(pageId: String) = shortLived(pageId, R.string.notif_cancelled)

    override fun answeredElsewhere(pageId: String) = shortLived(pageId, R.string.notif_answered_elsewhere)

    override fun missed(pageId: String, name: String?) {
        val title = if (name != null) {
            context.getString(R.string.notif_missed_from, name)
        } else {
            context.getString(R.string.notif_missed)
        }
        notify(
            pageId,
            base(AlertChannels.STATUS).setContentTitle(title).setAutoCancel(true).build()
        )
    }

    override fun acknowledgedUnknown(pageId: String) {
        val notification = base(AlertChannels.QUIET)
            .setContentTitle(context.getString(R.string.notif_acknowledged_elsewhere_title))
            .setAutoCancel(true)
            .build()
        notify(pageId, notification)
    }

    override fun senderAnswered(pageId: String, name: String?) {
        val title = if (name != null) {
            context.getString(R.string.notif_sender_answered, name)
        } else {
            context.getString(R.string.notif_sender_answered_unknown)
        }
        val notification = base(AlertChannels.STATUS)
            .setContentTitle(title)
            .setContentIntent(talk(pageId, TalkIntents.MODE_OUTGOING, name))
            .setAutoCancel(true)
            .build()
        notify(pageId, notification)
    }

    override fun senderState(pageId: String, state: String, name: String?) {
        val who = name ?: context.getString(R.string.unknown_caller)
        val title = when (state) {
            "failed" -> context.getString(R.string.notif_page_failed, who)
            else -> context.getString(R.string.notif_page_no_answer, who)
        }
        notify(pageId, base(AlertChannels.STATUS).setContentTitle(title).setAutoCancel(true).build())
    }

    override fun test() {
        val notification = base(AlertChannels.STATUS)
            .setContentTitle(context.getString(R.string.notif_test))
            .setAutoCancel(true)
            .build()
        manager?.notify(TEST_TAG, TEST_NOTIFICATION_ID, notification)
    }

    override fun clear(pageId: String) {
        manager?.cancel(pageId, PAGE_NOTIFICATION_ID)
    }

    override fun dropLeftovers() {
        val manager = manager ?: return
        manager.activeNotifications
            .filter { it.id == PAGE_NOTIFICATION_ID && it.tag != null }
            .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun shortLived(pageId: String, title: Int) {
        val notification = base(AlertChannels.STATUS)
            .setContentTitle(context.getString(title))
            .setAutoCancel(true)
            .setTimeoutAfter(SHORT_LIVED_MS)
            .build()
        notify(pageId, notification)
    }

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_launcher)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setWhen(System.currentTimeMillis())

    private fun notify(pageId: String, notification: Notification) {
        manager?.notify(pageId, PAGE_NOTIFICATION_ID, notification)
    }

    private fun talk(pageId: String, mode: String, name: String?): PendingIntent =
        TalkIntents.pendingIntent(context, pageId, mode, name)

    /** A broadcast to [AlertActionReceiver]; the page id in the intent data keeps each page's intent distinct. */
    private fun action(pageId: String, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlertActionReceiver::class.java)
            .setAction(action)
            .setData(TalkIntents.identity(pageId, action.substringAfterLast('.')))
            .putExtra(TalkIntents.EXTRA_PAGE_ID, pageId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val PAGE_NOTIFICATION_ID = 1
        const val TEST_NOTIFICATION_ID = 2
        const val TEST_TAG = "test"

        /** "Page cancelled" and "Answered on another phone" remove themselves after this long. */
        const val SHORT_LIVED_MS = 10_000L
    }
}
