package com.example.ptt.audio

import com.example.ptt.talk.ClipStarted
import com.example.ptt.talk.Incoming
import com.example.ptt.talk.IncomingOpus
import com.example.ptt.talk.LiveEnded
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
 * Plays the peer's transmissions: [enqueue] (called from a fast collector, only queues) feeds one dedicated playout
 * thread that owns the [JitterBuffer] (live audio), the [ClipQueue] (clips, and live transmissions held behind them
 * or behind this phone's own talking, docs/protocol.md section 13.8), the decoder and the sink. Live audio plays
 * first; the queue plays once the live transmission has played out. Nothing is written to disk and nothing about
 * frames is logged.
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

        class Event(val event: Incoming) : Msg

        class Busy(val on: Boolean) : Msg

        data object Release : Msg

        data object Flush : Msg

        data object DropCurrent : Msg

        data object Wake : Msg
    }

    private val inbox = LinkedBlockingQueue<Msg>()
    private val jitter = JitterBuffer()
    private val clips = ClipQueue()

    /** The queued transmission whose packets the decoder is playing, if any (else the decoder is the live one's). */
    private var clipPlaying: String? = null
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

    /**
     * Queues what the session reported; never blocks and never drops. A clip frame was already reported `heard`, so
     * the server will not send it again (docs/protocol.md section 13.5); the server bounds a replay (section 13.10).
     */
    fun enqueue(event: Incoming) {
        if (closed) return
        inbox.add(if (event is IncomingOpus) Msg.Frame(event) else Msg.Event(event))
    }

    /** This phone is capturing or sending: queued audio waits (section 13.8). */
    fun busy(on: Boolean) {
        if (!closed) inbox.add(Msg.Busy(on))
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
                val idle = jitter.drained && clips.isEmpty
                var msg = if (idle) inbox.take() else inbox.poll(TICK_MS, TimeUnit.MILLISECONDS)
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
            is Msg.Frame -> frame(msg.frame, now)

            is Msg.Event -> event(msg.event, now)

            is Msg.Busy -> clips.busy(msg.on)

            Msg.Release -> jitter.release()

            Msg.Flush -> {
                jitter.flush()
                clips.clear()
                discardSink()
                endTransmission()
            }

            Msg.DropCurrent -> {
                jitter.dropCurrent()
                if (clipPlaying != null) clips.dropHead()
                discardSink()
                endTransmission()
            }

            Msg.Wake -> Unit
        }
    }

    /** A packet goes to the queue when its transmission is a clip or held there, else it plays live. */
    private fun frame(f: IncomingOpus, now: Long) {
        if (clips.offer(f.transmissionId, f.seq, f.opus, now)) return
        if (jitter.transmissionId != f.transmissionId && clips.hold(f.transmissionId, now)) {
            clips.offer(f.transmissionId, f.seq, f.opus, now)
            return
        }
        if (jitter.push(f.transmissionId, f.seq, f.opus, now)) newTransmission()
    }

    private fun event(e: Incoming, now: Long) {
        when (e) {
            is LiveEnded -> {
                clips.liveEnded(e.transmissionId)
                if (jitter.transmissionId == e.transmissionId) jitter.release()
            }

            is ClipStarted -> clips.clip(e.transmissionId, e.lastSeq, e.acceptedBefore, now)

            is IncomingOpus -> frame(e, now)
        }
    }

    internal fun handleFrame(frame: IncomingOpus, now: Long) = handle(Msg.Frame(frame), now)

    internal fun handleEvent(event: Incoming, now: Long) = handle(Msg.Event(event), now)

    internal fun handleBusy(on: Boolean) = handle(Msg.Busy(on), 0)

    internal fun handleRelease() = handle(Msg.Release, 0)

    internal fun handleDropCurrent() = handle(Msg.DropCurrent, 0)

    internal fun handleFlush() = handle(Msg.Flush, 0)

    /** Plays everything that is due at [now]: the live transmission, and once it has played out, the queue. */
    internal fun tick(now: Long) {
        while (true) {
            when (val out = jitter.poll(now)) {
                null -> break
                is JitterOutput.Packet -> play(out.opus)
                is JitterOutput.Gap -> repeat(out.count) { conceal() }
            }
        }
        if (!jitter.drained) return
        if (clipPlaying == null && decoder != null) endTransmission()
        tickClips(now)
    }

    private fun tickClips(now: Long) {
        while (true) {
            when (val out = clips.poll(now)) {
                null -> break

                is ClipOutput.Packet -> {
                    if (out.transmissionId != clipPlaying) {
                        newTransmission()
                        clipPlaying = out.transmissionId
                    }
                    play(out.opus)
                }

                is ClipOutput.Done -> if (out.transmissionId == clipPlaying) endTransmission()
            }
        }
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
        clipPlaying = null
        stopSink()
        closeQuietly(decoder)
        decoder = null
        lastPcm = null
        concealed = 0
    }

    @Suppress("ReturnCount")
    private fun play(opus: ByteArray) {
        val d = decoder
        if (d == null) {
            conceal()
            return
        }
        val pcm = try {
            d.decode(opus)
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
        clips.clear()
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
        const val MAX_CONCEALED = 3
        const val MAX_DECODER_FAILURES = 5
        const val MS_PER_SECOND = 1000
    }
}
