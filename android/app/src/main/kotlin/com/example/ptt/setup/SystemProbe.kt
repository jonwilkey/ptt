package com.example.ptt.setup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.ptt.alert.AlertChannels

/**
 * Reads the Android settings the setup checklist reports. Every function returns null when the state
 * cannot be read reliably (a missing service, a hidden setting that this Android version does not have,
 * a [SecurityException]); the checklist then shows "check manually", never green.
 */
interface SystemProbe {
    /** The lower-case `Build.MANUFACTURER`, used to pick the OEM instructions. */
    val manufacturer: String

    /** `POST_NOTIFICATIONS` is granted (always true below Android 13, where it does not exist). */
    fun notificationPermission(): Boolean?

    /** The user has not switched this app's notifications off. */
    fun notificationsEnabled(): Boolean?

    /**
     * Best available signal for "this app's notifications show on the lock screen". Android has no API for
     * the per-app lock-screen setting, so this combines what can be read: false when the app's
     * notifications are off or any of its channels hides content on the lock screen (or is blocked), or the
     * global "notifications on lock screen" setting reads off; true only when that global setting is
     * readable and on and nothing above objects; null when the global setting cannot be read.
     */
    fun lockScreenNotifications(): Boolean?

    fun microphone(): Boolean?

    /** `canUseFullScreenIntent()` on Android 14+; the (install-time) permission below. */
    fun fullScreenIntent(): Boolean?

    fun dndAccess(): Boolean?

    fun ignoringBatteryOptimizations(): Boolean?

    fun canInstallPackages(): Boolean?

    /**
     * The urgent-page channel is set to bypass Do Not Disturb, which Android only keeps if it was set while this
     * app held Do Not Disturb access. Null if the channel does not exist yet.
     */
    fun urgentChannelBypassesDnd(): Boolean?
}

/** [SystemProbe] backed by the real system services. */
class AndroidSystemProbe(private val context: Context) : SystemProbe {
    override val manufacturer: String = Build.MANUFACTURER.orEmpty().lowercase()

    private val notifications: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    override fun notificationPermission(): Boolean? = if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.TIRAMISU
    ) {
        granted(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }

    override fun notificationsEnabled(): Boolean? = read { notifications?.areNotificationsEnabled() }

    override fun lockScreenNotifications(): Boolean? = read {
        val manager = notifications ?: return@read null
        if (!manager.areNotificationsEnabled()) return@read false
        if (manager.notificationChannels.any { hidesOnLockScreen(it) }) return@read false
        when (readGlobalLockScreenSetting()) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    override fun microphone(): Boolean? = granted(Manifest.permission.RECORD_AUDIO)

    override fun fullScreenIntent(): Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        read { notifications?.canUseFullScreenIntent() }
    } else {
        granted(Manifest.permission.USE_FULL_SCREEN_INTENT)
    }

    override fun dndAccess(): Boolean? = read { notifications?.isNotificationPolicyAccessGranted }

    override fun ignoringBatteryOptimizations(): Boolean? = read {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
    }

    override fun canInstallPackages(): Boolean? = read { context.packageManager.canRequestPackageInstalls() }

    override fun urgentChannelBypassesDnd(): Boolean? =
        read { notifications?.getNotificationChannel(AlertChannels.PAGE_URGENT)?.canBypassDnd() }

    private fun granted(permission: String): Boolean? =
        read { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }

    private fun hidesOnLockScreen(channel: NotificationChannel): Boolean =
        channel.lockscreenVisibility == android.app.Notification.VISIBILITY_SECRET ||
            channel.importance == NotificationManager.IMPORTANCE_NONE

    /**
     * `Settings.Secure.lock_screen_show_notifications`: an undocumented (hidden constant) key. 1 = on, 0 = off,
     * null when the device does not have it, in which case the row is not reported as passing.
     */
    private fun readGlobalLockScreenSetting(): Int? = try {
        Settings.Secure.getInt(context.contentResolver, "lock_screen_show_notifications")
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> read(block: () -> T?): T? = try {
        block()
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
