package com.example.ptt.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.example.ptt.R

/**
 * The notification channels. Android freezes a channel's sound, vibration and importance once it exists
 * (the user may change them, the app may not), so every attribute here is deliberate:
 *
 * - [PAGE_NORMAL]: high importance (heads-up), the default notification sound on the notification stream
 *   (so silent mode silences it and the phone only vibrates), a short vibration, shown in full on the lock
 *   screen. Does not bypass Do Not Disturb.
 * - [PAGE_URGENT]: high importance, bypasses Do Not Disturb, alarm audio attributes, a long vibration,
 *   public on the lock screen. It carries no sound of its own: the tone is looped by [AlertService]
 *   (a channel sound would play once, and again on top of the loop).
 * - [STATUS]: default importance, for the short messages (cancelled, missed, answered, failed, test).
 * - [QUIET]: low importance with no sound or vibration, for a page that was acknowledged (still answerable
 *   from the lock screen).
 * - [SERVICE]: low importance, for the foreground service's ongoing notice and background sync, so that a
 *   user who blocks that notice does not also hide acknowledged-but-answerable pages.
 *
 * Android ignores bypass-DND on a channel created while the app lacks Do Not Disturb access, and the grant
 * comes after first launch (setup checklist). So [create] is repeated: at every app start, when the checklist
 * sees the access, and before each urgent notification; an app with the access can set the flag on an
 * existing channel the user has not locked.
 */
object AlertChannels {
    const val PAGE_NORMAL = "page_normal"
    const val PAGE_URGENT = "page_urgent"
    const val STATUS = "page_status"
    const val QUIET = "page_quiet"

    /** Foreground notices of [AlertService] and background sync; apart from [QUIET] so blocking them is harmless. */
    const val SERVICE = "alert_service"

    /** Off, buzz, pause, buzz (milliseconds): a short double tap. */
    @Suppress("MagicNumber")
    val NORMAL_VIBRATION = longArrayOf(0, 200, 120, 200)

    /** Three long, strong buzzes. */
    @Suppress("MagicNumber")
    val URGENT_VIBRATION = longArrayOf(0, 900, 300, 900, 300, 900)

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(normal(context), urgent(context), status(context), quiet(context), service(context))
        )
    }

    private fun normal(context: Context) = NotificationChannel(
        PAGE_NORMAL,
        context.getString(R.string.channel_page_normal),
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = context.getString(R.string.channel_page_normal_description)
        setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        enableVibration(true)
        vibrationPattern = NORMAL_VIBRATION
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setShowBadge(false)
    }

    private fun urgent(context: Context) = NotificationChannel(
        PAGE_URGENT,
        context.getString(R.string.channel_page_urgent),
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = context.getString(R.string.channel_page_urgent_description)
        setBypassDnd(true)
        setSound(
            null,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        enableVibration(true)
        vibrationPattern = URGENT_VIBRATION
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setShowBadge(false)
    }

    private fun status(context: Context) = NotificationChannel(
        STATUS,
        context.getString(R.string.channel_status),
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = context.getString(R.string.channel_status_description)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

    private fun service(context: Context) = NotificationChannel(
        SERVICE,
        context.getString(R.string.channel_service),
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = context.getString(R.string.channel_service_description)
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
    }

    private fun quiet(context: Context) = NotificationChannel(
        QUIET,
        context.getString(R.string.channel_quiet),
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = context.getString(R.string.channel_quiet_description)
        setSound(null, null)
        enableVibration(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setShowBadge(false)
    }
}
