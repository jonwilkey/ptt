package com.example.ptt.audio

import com.example.ptt.talk.IncomingOpus
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Where decoded PCM goes (an `AudioTrack` in production). [write] may block until the device has room. */
interface AudioSink : AutoCloseable {
    /** Called before the first [write] of each transmission: the device starts from a known (stopped) state. */
    fun start()

    fun write(pcm: ShortArray)

    /** The transmission is over: what was written plays out, then the device idles until the next [start]. */
    fun stop()

    /** Discards what was written and not yet played (session ended). */
    fun flush()
}

/**
 * Plays the peer's transmission: [enqueue] (called from a fast collector, only queues) feeds one dedicated playout
 * thread that owns the [JitterBuffer], the decoder and the sink. Nothing is written to disk and nothing about frames
 * is logged.
 *
 * Per transmission it creates a fresh decoder (a fresh Opus stream pairs with a fresh decoder, so the pre-skip is
 * applied once and no state leaks between talkers), eagerly on the transmission's first packet so the codec start
 * is hidden by the jitter delay.
 *
 * Packet-loss concealment. The platform Opus decoder takes no "lost packet" input (an empty access unit is invalid),
 * so a [JitterOutput.Gap] is concealed here: the last decoded frame is repeated at half amplitude, then a quarter,
 * then an eighth, and after that silence. This is only meant to hide a 20-60 ms hole in speech.
 *
 * Tests drive [handle]/[tick] directly with a fake clock; [start] runs the same two on the playout thread.
 */
@Suppress("TooManyFunctions")
class Player(
    private val decoderFactory: () -> OpusDecoder,
    private val sinkFactory: (sampleRate: Int) -> AudioSink,
    private val clock: () -> Long
) {
    private sealed interface Msg {
        class Frame(val frame: IncomingOpus) : Msg

        data object Release : Msg

        data object Flush : Msg

        data object DropCurrent : Msg

        data object Wake : Msg
    }

    private val inbox = LinkedBlockingQueue<Msg>()
    private val jitter = JitterBuffer()
    private var decoder: OpusDecoder? = null
    private var decoderFailures = 0
    private var sink: AudioSink? = null
    private var sinkRate = 0
    private var sinkRunning = false
    private var sinkBroken = false
    private var lastPcm: ShortArray? = null
    private var concealed = 0

    @Volatile
    private var closed = false
    private var thread: Thread? = null

    /** Names of the threads that ran playout work, for the "one playout thread" test. */
    internal val playoutThread: Thread? get() = thread

    /** Queues a packet; never blocks. Drops it if the playout thread is far behind (a stall, not normal operation). */
    fun enqueue(frame: IncomingOpus) {
        if (!closed && inbox.size < MAX_QUEUED) inbox.add(Msg.Frame(frame))
    }

    /** The peer's floor was released: what is buffered plays out, later packets of that transmission are dropped. */
    fun release() {
        if (!closed) inbox.add(Msg.Release)
    }

    /**
     * Silence the current transmission now (audio focus was lost): what is buffered and what the device holds is
     * discarded, and the rest of this transmission's packets are dropped as they arrive. The next one plays.
     */
    fun dropCurrent() {
        if (!closed) inbox.add(Msg.DropCurrent)
    }

    /** Session over: discard everything buffered. */
    fun flush() {
        if (!closed) inbox.add(Msg.Flush)
    }

    @Synchronized
    fun start() {
        if (thread != null || closed) return
        thread = Thread(::run, "ptt-playout").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Stops the thread and releases the decoder and the sink. */
    fun close() {
        closed = true
        inbox.add(Msg.Wake)
        val t = synchronized(this) { thread }
        if (t == null) {
            releaseAll()
        } else {
            t.join(JOIN_MS)
        }
    }

    private fun run() {
        try {
            while (!closed) {
                // Idle (nothing buffered): sleep until a message arrives; otherwise wake for the next slot.
                var msg = if (jitter.drained) inbox.take() else inbox.poll(TICK_MS, TimeUnit.MILLISECONDS)
                while (msg != null) {
                    handle(msg, clock())
                    msg = inbox.poll()
                }
                tick(clock())
            }
        } catch (_: InterruptedException) {
            // closing
        } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
            // Never let an audio thread crash the app: playback degrades to silence.
            closed = true
        } finally {
            releaseAll()
        }
    }

    private fun handle(msg: Msg, now: Long) {
        when (msg) {
            is Msg.Frame -> {
                val f = msg.frame
                if (jitter.push(f.transmissionId, f.seq, f.opus, now)) newTransmission()
            }

            Msg.Release -> jitter.release()

            Msg.Flush -> {
                jitter.flush()
                discardSink()
                endTransmission()
            }

            Msg.DropCurrent -> {
                jitter.dropCurrent()
                discardSink()
                endTransmission()
            }

            Msg.Wake -> Unit
        }
    }

    internal fun handleFrame(frame: IncomingOpus, now: Long) = handle(Msg.Frame(frame), now)

    internal fun handleRelease() = handle(Msg.Release, 0)

    internal fun handleDropCurrent() = handle(Msg.DropCurrent, 0)

    internal fun handleFlush() = handle(Msg.Flush, 0)

    /** Plays everything that is due at [now]. */
    internal fun tick(now: Long) {
        while (true) {
            when (val out = jitter.poll(now)) {
                null -> break
                is JitterOutput.Packet -> play(out)
                is JitterOutput.Gap -> repeat(out.count) { conceal() }
            }
        }
        if (jitter.drained && decoder != null) endTransmission()
    }

    private fun newTransmission() {
        endTransmission()
        decoderFailures = 0
        // A sink failure (e.g. the audio server restarted) is retried with every new transmission.
        sinkBroken = false
        decoder = try {
            decoderFactory()
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun endTransmission() {
        stopSink()
        closeQuietly(decoder)
        decoder = null
        lastPcm = null
        concealed = 0
    }

    @Suppress("ReturnCount")
    private fun play(packet: JitterOutput.Packet) {
        val d = decoder
        if (d == null) {
            conceal()
            return
        }
        val pcm = try {
            d.decode(packet.opus)
        } catch (_: IOException) {
            return failed()
        } catch (_: IllegalStateException) {
            return failed()
        }
        decoderFailures = 0
        if (pcm.samples.isEmpty()) return
        concealed = 0
        lastPcm = pcm.samples
        write(pcm.samples, pcm.sampleRate)
    }

    private fun failed() {
        if (++decoderFailures >= MAX_DECODER_FAILURES) {
            closeQuietly(decoder)
            decoder = null
        }
        conceal()
    }

    private fun conceal() {
        val last = lastPcm
        val n = ++concealed
        val frame = if (last == null || n > MAX_CONCEALED) {
            ShortArray(last?.size ?: (AudioSpec.DECODER_RATE * AudioSpec.FRAME_MS / MS_PER_SECOND))
        } else {
            ShortArray(last.size) { (last[it].toInt() shr n).toShort() }
        }
        write(frame, if (sinkRate != 0) sinkRate else AudioSpec.DECODER_RATE)
    }

    private fun write(pcm: ShortArray, rate: Int) {
        if (sinkBroken) return
        if (sink == null || sinkRate != rate) {
            closeQuietly(sink)
            sinkRunning = false
            sink = openSink(rate)
            sinkRate = rate
            if (sink == null) {
                sinkBroken = true
                return
            }
        }
        try {
            if (!sinkRunning) {
                sink?.start()
                sinkRunning = true
            }
            sink?.write(pcm)
        } catch (_: IllegalStateException) {
            closeQuietly(sink)
            sink = null
            sinkRunning = false
            sinkBroken = true
        }
    }

    private fun openSink(rate: Int): AudioSink? = try {
        sinkFactory(rate)
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    private fun stopSink() {
        if (!sinkRunning) return
        sinkRunning = false
        try {
            sink?.stop()
        } catch (_: IllegalStateException) {
            // already gone
        }
    }

    private fun discardSink() {
        if (!sinkRunning) return
        sinkRunning = false // flushing pauses the device; the next write starts it again
        try {
            sink?.flush()
        } catch (_: IllegalStateException) {
            // already gone
        }
    }

    private fun releaseAll() {
        endTransmission()
        closeQuietly(sink)
        sink = null
        jitter.flush()
        inbox.clear()
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

    private companion object {
        const val TICK_MS = 10L
        const val JOIN_MS = 1_000L
        const val MAX_QUEUED = 256
        const val MAX_CONCEALED = 3
        const val MAX_DECODER_FAILURES = 5
        const val MS_PER_SECOND = 1000
    }
}
