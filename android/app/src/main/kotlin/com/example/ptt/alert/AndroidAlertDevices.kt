package com.example.ptt.alert

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.edit
import com.example.ptt.R
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "AlertDevices"

/** Loops of the urgent tone: one [playOnce] plays the bundled clip through `MediaPlayer` on the alarm stream. */
class AndroidAlertSound(private val context: Context) : AlertSound {
    override suspend fun playOnce() = suspendCancellableCoroutine<Unit> { continuation ->
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val player = MediaPlayer.create(context, R.raw.urgent_tone, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (player == null) {
            Log.w(TAG, "urgent tone could not be created")
            continuation.resume(Unit) { _, _, _ -> }
            return@suspendCancellableCoroutine
        }
        val finish = {
            player.release()
            if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> }
        }
        player.setOnCompletionListener { finish() }
        player.setOnErrorListener { _, _, _ ->
            finish()
            true
        }
        continuation.invokeOnCancellation { player.release() }
        player.start()
    }
}

/** The long, strong pattern, repeated until [stop], on the alarm usage so it is not muted by silent mode. */
class AndroidAlertVibrator(context: Context) : AlertVibrator {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    @Suppress("DEPRECATION")
    override fun start() {
        val effect = VibrationEffect.createWaveform(AlertChannels.URGENT_VIBRATION, 0)
        vibrator?.vibrate(
            effect,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
    }

    override fun stop() {
        vibrator?.cancel()
    }

    @Suppress("DEPRECATION")
    override fun pulse() {
        vibrator?.vibrate(
            VibrationEffect.createWaveform(AlertChannels.NORMAL_VIBRATION, -1),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
    }
}

/** Silent ringer, or Do Not Disturb filtering notifications: the channel then neither sounds nor vibrates. */
class AndroidRingerState(private val context: Context) : RingerState {
    override fun quiet(): Boolean {
        val silent = context.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_SILENT
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        val filtering = filter != null &&
            filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return silent || filtering
    }
}

class AndroidAlarmVolume(context: Context) : AlarmVolume {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var pending: Int?
        get() = prefs.getInt(KEY_PENDING, NONE).takeIf { it >= 0 }
        // commit(), not apply(): the value must be on disk before the volume is raised.
        set(value) = prefs.edit(commit = true) {
            if (value ==
                null
            ) {
                remove(KEY_PENDING)
            } else {
                putInt(KEY_PENDING, value)
            }
        }

    override fun current(): Int = audio?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0

    override fun max(): Int = audio?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0

    override fun set(volume: Int) {
        try {
            audio?.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        } catch (_: SecurityException) {
            // Do Not Disturb policy can refuse volume changes until the user grants access (checklist item).
            Log.w(TAG, "alarm volume change refused")
        }
    }

    private companion object {
        const val PREFS = "alert_state"
        const val KEY_PENDING = "pending_alarm_volume"
        const val NONE = -1
    }
}

class AndroidAlertWakeLock(context: Context) : AlertWakeLock {
    private val lock: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ptt:alert")
        ?.apply { setReferenceCounted(false) }

    override fun acquire(timeoutMs: Long) {
        lock?.acquire(timeoutMs)
    }

    override fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }
}

/** Starts [AlertService] as a foreground service (allowed because it is called while handling a high-priority push). */
class AndroidAlertServiceControl(private val context: Context) : AlertServiceControl {
    override fun start() {
        try {
            context.startForegroundService(Intent(context, AlertService::class.java))
        } catch (e: IllegalStateException) {
            // Background-start restrictions: the notification still alerts, the tone and loop do not.
            Log.w(TAG, "alert service could not start: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The service stops itself. A stopService call while a start is still pending (the service not yet created)
     * can end in "did not call startForeground"; a pending start finds nothing ringing in `onStartCommand`
     * and stops itself there.
     */
    override fun stop() {
        AlertService.current?.stopSelf()
    }

    override fun refresh(top: IncomingPage?) {
        AlertService.current?.showNotice(top)
    }
}
