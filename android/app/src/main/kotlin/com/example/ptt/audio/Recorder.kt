package com.example.ptt.audio

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The microphone (an `AudioRecord` in production). Created idle: [start] begins capturing. */
interface PcmSource : AutoCloseable {
    /** Begins capturing; before this the microphone delivers nothing. */
    fun start()

    /** Blocks until [buffer] is full (one 20 ms frame). Returns the samples read, or a negative value on error. */
    fun read(buffer: ShortArray): Int
}

enum class StopReason {
    /** [Recorder.stop] was called. */
    REQUESTED,

    /** The client clock reached [Recorder.MAX_CAPTURE_MS], just before the server's 60 s cap. */
    MAX_DURATION,

    /** `sendOpus` returned false: the phone no longer holds the floor, or the socket is gone. */
    SEND_REFUSED,

    /** The microphone could not be opened or stopped delivering (permission revoked, another app has it). */
    MIC_UNAVAILABLE,

    /** The Opus encoder could not be created or failed. */
    CODEC_UNAVAILABLE
}

/**
 * One push-to-talk press worth of capture, on its own thread. Single use: create a new one per press.
 *
 * Lifecycle: [prewarm] (the button went down) creates the microphone object and the encoder on the capture thread
 * but starts no recording and sends nothing; [start] (the floor was granted to this phone) starts recording and
 * from then on every 20 ms packet goes to `sendOpus` from that same thread; [stop] ends it from any thread.
 *
 * Why pre-warm: opening a MediaCodec and an AudioRecord takes tens of milliseconds, and the grant takes a network
 * round trip. Doing the opening while the request is in flight means the first frame leaves right after the grant
 * (the server wants one within 3 s; this should take well under 0.5 s). The microphone is not recording before the
 * grant, so no sound is captured, buffered or sent for a request that is never granted; a pre-warm that is not
 * followed by [start] within [PREWARM_TIMEOUT_MS] shuts itself down.
 *
 * Capture ends when: [stop], `sendOpus` returns false, the microphone or codec fails, or [MAX_CAPTURE_MS] have passed
 * on [clock] since [start]. [onStopped] runs on the capture thread for every reason except [StopReason.REQUESTED].
 * The packets are the raw 20 ms Opus output: this class never seals or resends anything.
 */
class Recorder(
    private val sendOpus: (ByteArray) -> Boolean,
    private val sourceFactory: () -> PcmSource,
    private val encoderFactory: () -> OpusEncoder,
    private val clock: () -> Long,
    private val prewarmTimeoutMs: Long = PREWARM_TIMEOUT_MS,
    private val onStopped: (StopReason) -> Unit
) {
    enum class Phase { IDLE, PREWARM, CAPTURING, DONE }

    private sealed interface Command {
        class Start(val grantedAtMs: Long) : Command

        data object Stop : Command
    }

    private val commands = LinkedBlockingQueue<Command>()

    @Volatile
    private var stopRequested = false

    @Volatile
    var phase = Phase.IDLE
        private set

    /** The capture thread (null before [prewarm]); every `sendOpus` call is made on it. */
    @Volatile
    internal var thread: Thread? = null
        private set

    /** Starts the capture thread and opens the microphone and encoder. Idempotent. */
    @Synchronized
    fun prewarm() {
        if (phase != Phase.IDLE) return
        phase = Phase.PREWARM
        thread = Thread(::run, "ptt-capture").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * The floor was granted: begin recording and sending. Ignored once finished; starts the pre-warm if needed.
     * [grantedAtMs] (on [clock]) is when the grant reached this phone; the 59.5 s cap counts from it, not from the
     * moment the microphone happens to start delivering.
     */
    fun start(grantedAtMs: Long = clock()) {
        prewarm()
        commands.add(Command.Start(grantedAtMs))
    }

    /** Ends the pre-warm or the capture. Safe from any thread, any number of times. */
    fun stop() {
        stopRequested = true
        commands.add(Command.Stop)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun run() {
        var reason = StopReason.REQUESTED
        var source: PcmSource? = null
        var encoder: OpusEncoder? = null
        var stage = StopReason.MIC_UNAVAILABLE
        try {
            source = sourceFactory()
            stage = StopReason.CODEC_UNAVAILABLE
            encoder = encoderFactory()
            val command = commands.poll(prewarmTimeoutMs, TimeUnit.MILLISECONDS)
            if (command is Command.Start && !stopRequested) {
                phase = Phase.CAPTURING
                reason = capture(source, encoder, command.grantedAtMs)
            }
        } catch (_: IOException) {
            reason = stage
        } catch (_: SecurityException) {
            reason = StopReason.MIC_UNAVAILABLE
        } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
            // Codec and audio-device failures are all unchecked; an audio thread must never crash the app.
            reason = stage
        } finally {
            closeQuietly(source)
            closeQuietly(encoder)
            phase = Phase.DONE
        }
        if (reason != StopReason.REQUESTED) onStopped(reason)
    }

    @Suppress("ReturnCount")
    private fun capture(source: PcmSource, encoder: OpusEncoder, startedAt: Long): StopReason {
        try {
            source.start()
        } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
            return StopReason.MIC_UNAVAILABLE
        }
        val frame = ShortArray(AudioSpec.FRAME_SAMPLES)
        while (!stopRequested) {
            if (clock() - startedAt >= MAX_CAPTURE_MS) return StopReason.MAX_DURATION
            if (readFrame(source, frame) != frame.size) return StopReason.MIC_UNAVAILABLE
            for (packet in encoder.encode(frame)) {
                if (stopRequested) return StopReason.REQUESTED
                if (!sendOpus(packet)) return StopReason.SEND_REFUSED
            }
        }
        return StopReason.REQUESTED
    }

    /** A microphone exception is the microphone's failure, not the codec's. */
    private fun readFrame(source: PcmSource, frame: ShortArray): Int = try {
        source.read(frame)
    } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
        -1
    }

    private fun closeQuietly(c: AutoCloseable?) {
        try {
            c?.close()
        } catch (_: IllegalStateException) {
            // already gone
        } catch (_: IOException) {
            // already gone
        }
    }

    companion object {
        /** The server ends a transmission 60 s after the grant; stop half a second early on the client clock. */
        const val MAX_CAPTURE_MS = 59_500L
        const val PREWARM_TIMEOUT_MS = 10_000L
    }
}
