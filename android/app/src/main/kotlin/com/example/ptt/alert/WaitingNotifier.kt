package com.example.ptt.alert

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.ptt.R
import com.example.ptt.talk.TalkIntents

/** The sender's "Waiting for ..." notification, rendered by [SenderWaiting]. */
interface WaitingNotices {
    /** Posts or updates (silently) the waiting notification of [pageId]; [state] is the sender row's state. */
    fun show(pageId: String, name: String?, state: String)

    /** Removes every waiting notification except the one of [keep] (null: all). Safe to repeat. */
    fun retain(keep: String?)
}

/**
 * Ongoing, silent notification for a page this phone sent. It has its own notification id (not
 * [PageNotifier.PAGE_NOTIFICATION_ID]), so the "answered" / "failed" notices of the same page, which use the
 * page tag with that id, appear beside it without replacing it or being removed by it, and the leftover sweep
 * of dead-process ringing notifications does not touch it. The channel is the existing quiet one (low
 * importance, no sound). It has no actions: tapping it only reopens the page's talk screen.
 */
class WaitingNotifier(private val context: Context) : WaitingNotices {
    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    override fun show(pageId: String, name: String?, state: String) {
        val who = name?.let { context.getString(R.string.notif_waiting_title, it) }
            ?: context.getString(R.string.notif_waiting_unknown)
        val notification = NotificationCompat.Builder(context, AlertChannels.QUIET)
            .setSmallIcon(R.drawable.ic_launcher)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(who)
            .setContentText(context.getString(label(state)))
            .setContentIntent(TalkIntents.pendingIntent(context, pageId, TalkIntents.MODE_OUTGOING, name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        manager?.notify(pageId, WAITING_NOTIFICATION_ID, notification)
    }

    override fun retain(keep: String?) {
        val manager = manager ?: return
        manager.activeNotifications
            .filter { it.id == WAITING_NOTIFICATION_ID && it.tag != null && it.tag != keep }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun label(state: String) = when (state) {
        "not_delivered" -> R.string.talk_not_delivered
        "delivered" -> R.string.talk_delivered
        "acknowledged" -> R.string.talk_acknowledged
        else -> R.string.talk_sending
    }

    companion object {
        const val WAITING_NOTIFICATION_ID = 4
    }
}
