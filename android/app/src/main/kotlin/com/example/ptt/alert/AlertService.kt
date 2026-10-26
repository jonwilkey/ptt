package com.example.ptt.alert

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.ptt.R
import com.example.ptt.talk.TalkIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the process alive while a page rings (foreground service, type `specialUse`). The policy lives in
 * [AlertEngine]: it starts and stops this service as pages start and stop ringing, and plays the sound itself.
 * This class only provides the foreground state, and on destroy (for whatever reason) makes the engine
 * undo everything ringing did: tone, vibration, alarm volume and wake lock.
 */
@AndroidEntryPoint
class AlertService : Service() {
    @Inject
    lateinit var engine: AlertEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        // Visible to the engine only once the service is foreground: a stopSelf() from the engine before this
        // point could otherwise end a service that never called startForeground.
        current = this
        // Started with nothing to ring (the page ended before the service came up, or the process was
        // recreated): leave at once; the engine's own stop() may already have run.
        if (!engine.isAlerting) stopSelf()
        return START_NOT_STICKY
    }

    /** Re-posts the foreground notice under its id when the page it opens changes. */
    fun showNotice(top: IncomingPage?) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this, top))
    }

    override fun onDestroy() {
        if (current === this) current = null
        engine.serviceDestroyed()
        super.onDestroy()
    }

    private fun startForeground() {
        AlertChannels.create(this)
        val notification = notification(this, engine.topRinging())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Distinct from the page notifications, which are posted with the page id as tag. */
        const val NOTIFICATION_ID = 3

        /** The running instance, so that the engine can ask it to stop itself. */
        @Volatile
        var current: AlertService? = null
            private set

        /** Tapping the notice opens the highest-level ringing page. */
        fun notification(context: Context, top: IncomingPage?): Notification = builder(context, top).build()

        private fun builder(context: Context, top: IncomingPage?) = NotificationCompat.Builder(
            context,
            AlertChannels.SERVICE
        )
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .apply {
                if (top != null) {
                    val mode = if (top.urgent) TalkIntents.MODE_INCOMING else TalkIntents.MODE_ANSWER
                    setContentIntent(TalkIntents.pendingIntent(context, top.pageId, mode, top.fromName))
                }
            }
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }
}
