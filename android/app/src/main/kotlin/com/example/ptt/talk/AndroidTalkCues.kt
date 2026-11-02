package com.example.ptt.talk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * [TalkCues] on Android: cues run one at a time on a single background thread (at most two waiting; later ones are
 * dropped), each tone going into a one-shot `AudioTrack` with `USAGE_VOICE_COMMUNICATION_SIGNALLING` — a signalling
 * tone, which does not request audio focus and is not ducked by media — and the buzz is a one-shot vibration effect.
 * Everything is best-effort: a cue failing never affects the session, and nothing blocks the main thread.
 */
class AndroidTalkCues(context: Context) : TalkCues {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val executor: ExecutorService = boundedCueExecutor()

    override fun play(cue: Cue) {
        executor.execute {
            // The vibration is async, so start it first: it lands with the tone and survives a tone failure.
            if (cue.vibrates) buzz()
            try {
                playTone(Cues.pcm(cue))
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Best-effort: the cue must never take the session down.
                Log.w(TAG, "cue ${cue.name} failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Plays [pcm] to completion, then releases the track. */
    private fun playTone(pcm: ShortArray) {
        val min = AudioTrack.getMinBufferSize(
            Tones.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Tones.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            // A static track stays in PLAYING after the buffer ends, so wait out its length before releasing.
            Thread.sleep(pcm.size * MS_PER_S / Tones.SAMPLE_RATE + TAIL_MS)
        } finally {
            track.release()
        }
    }

    private fun buzz() {
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Best-effort (no permission, or no hardware): the tone already said it.
            Log.w(TAG, "buzz failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "TalkCues"
        const val BUZZ_MS = 100L
        const val MS_PER_S = 1000L
        const val TAIL_MS = 40L
    }
}

/** One daemon thread running cues in order; with [maxQueued] waiting, a new cue is dropped rather than queued. */
internal fun boundedCueExecutor(maxQueued: Int = 2): ExecutorService = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(maxQueued),
    { r -> Thread(r, "talk-cues").apply { isDaemon = true } },
    ThreadPoolExecutor.DiscardPolicy()
)
