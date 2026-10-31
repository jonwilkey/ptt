package com.example.ptt.audio

import java.util.TreeMap

/** What the playout clock wants next from a [JitterBuffer]. */
sealed interface JitterOutput {
    /** The next packet in order. */
    class Packet(val seq: Int, val opus: ByteArray) : JitterOutput

    /** [count] packets that never arrived (or arrived too late): conceal them. */
    class Gap(val count: Int) : JitterOutput
}

/**
 * Reorders the peer's Opus packets and paces them for playback. Pure Kotlin, single-threaded (the playout thread owns
 * it), time injected as `nowMs` so tests are deterministic.
 *
 * Model. A transmission (one transmission id) is a stream of 20 ms slots numbered by `seq`. The first packet seen
 * anchors the schedule: slot `s` plays at `anchorMs + delayMs + (s - anchorSeq) * 20`. [poll] returns the packets
 * whose slot is due, in seq order:
 * - the packet for the due slot is there: [JitterOutput.Packet];
 * - it is missing but a later packet is buffered: the packet is lost (the server drops under load): [JitterOutput.Gap]
 *   so the player can conceal it, and the schedule moves on (a run of more than [MAX_GAP_FRAMES] lost packets is a
 *   network outage, not jitter: the schedule re-anchors on the packet that ended it, which refills the buffer);
 * - it is missing and nothing later is buffered: nothing is returned (the stream ended, or the buffer ran dry). When
 *   the next packet arrives after a dry spell, the schedule is re-anchored ([push] does it) and the delay grows.
 * A packet whose slot has passed (seq below the next one due) or that repeats a buffered seq is dropped.
 *
 * Adaptive delay (spec: 120 ms, adaptive 60-300 ms). The delay is
 * - at the start of each transmission: four times the smoothed inter-arrival jitter of earlier transmissions (the
 *   RFC 3550 estimator, `J += (|D| - J) / 16`, `D` = arrival spacing minus seq spacing), rounded up to whole frames
 *   and clamped to 60-300 ms. The estimate starts at 30 ms, so the first transmission begins at 120 ms;
 * - during a transmission only ever larger: +20 ms (up to 300) each time the buffer runs dry or a packet arrives
 *   after its slot. It shrinks again at the next transmission's start if the network has calmed down.
 *
 * Transmission lifecycle: a new transmission id resets everything (the previous remainder is discarded; the floor
 * moves on only after a release plus a new press). [release] marks the current transmission over: what is buffered
 * still plays out, later packets for that id are dropped, and [drained] turns true when it is empty. [flush] throws
 * everything away (session ended).
 */
@Suppress("TooManyFunctions")
class JitterBuffer(private val frameMs: Int = AudioSpec.FRAME_MS) {
    private val packets = TreeMap<Int, ByteArray>()
    private var tid: String? = null
    private var released = false
    private var nextSeq: Int? = null
    private var started = false
    private var anchorMs = 0L
    private var anchorSeq = 0
    private var delayMs = INITIAL_DELAY_MS
    private var jitter = INITIAL_JITTER_MS
    private var lastArrivalMs: Long? = null
    private var lastArrivalSeq = 0

    /** Packets dropped as late, duplicate or after release since creation (diagnostics for tests; never logged). */
    var dropped = 0
        private set

    /** The transmission being played, or null when idle. */
    val transmissionId: String? get() = tid

    /** The current delay in ms (60-300). */
    val currentDelayMs: Int get() = delayMs

    /** True when nothing is buffered after a [release] (the transmission has played out). */
    val drained: Boolean get() = tid == null || (released && packets.isEmpty())

    /** Adds a packet. Returns true if the transmission id changed (a reset happened). */
    fun push(transmissionId: String, seq: Int, opus: ByteArray, nowMs: Long): Boolean {
        val isNew = transmissionId != tid
        if (isNew) startTransmission(transmissionId)
        if (released) {
            dropped++
            return isNew
        }
        val next = nextSeq
        val late = started && next != null && seq < next
        if (late || packets.containsKey(seq)) {
            dropped++
            // A packet that arrives after its slot says the buffer is too short.
            if (late) grow()
        } else {
            accept(seq, opus, nowMs)
        }
        return isNew
    }

    private fun startTransmission(id: String) {
        packets.clear()
        tid = id
        released = false
        nextSeq = null
        started = false
        lastArrivalMs = null
        val wanted = (jitter * JITTER_FACTOR).toInt().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        delayMs = roundUp(wanted).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    private fun accept(seq: Int, opus: ByteArray, nowMs: Long) {
        trackJitter(seq, nowMs)
        val next = nextSeq
        when {
            next == null -> anchor(seq, nowMs)

            // Reordered before anything played: the earlier packet simply goes first.
            !started -> nextSeq = minOf(next, seq)

            packets.isEmpty() && nowMs > slotMs(next) + frameMs -> {
                // The buffer ran dry and played out everything: rebuffer from this packet, with a longer delay.
                grow()
                anchor(seq, nowMs)
            }
        }
        packets[seq] = opus
    }

    private fun anchor(seq: Int, nowMs: Long) {
        anchorSeq = seq
        anchorMs = nowMs
        nextSeq = seq
    }

    private fun trackJitter(seq: Int, nowMs: Long) {
        val last = lastArrivalMs
        if (last != null && seq > lastArrivalSeq) {
            val deviation = kotlin.math.abs((nowMs - last) - (seq - lastArrivalSeq).toLong() * frameMs)
            jitter += (deviation - jitter) / JITTER_SMOOTHING
        }
        if (last == null || seq > lastArrivalSeq) {
            lastArrivalMs = nowMs
            lastArrivalSeq = seq
        }
    }

    private fun slotMs(seq: Int): Long = anchorMs + delayMs + (seq - anchorSeq).toLong() * frameMs

    /** The next output that is due at [nowMs], or null. Call repeatedly until null. */
    @Suppress("ReturnCount")
    fun poll(nowMs: Long): JitterOutput? {
        val next = nextSeq ?: return null
        if (packets.isEmpty() || nowMs < slotMs(next)) return null
        started = true
        val packet = packets.remove(next)
        if (packet != null) {
            nextSeq = next + 1
            return JitterOutput.Packet(next, packet)
        }
        val later = packets.firstKey()
        val lost = later - next
        return if (lost > MAX_GAP_FRAMES) {
            anchor(later, nowMs)
            JitterOutput.Gap(1)
        } else {
            nextSeq = next + 1
            JitterOutput.Gap(1)
        }
    }

    /** The floor was released: play out what is buffered, drop what arrives for this id from now on. */
    fun release() {
        if (tid != null) released = true
    }

    /** Drops what is buffered and ignores the rest of the current transmission (it stays "released"). */
    fun dropCurrent() {
        packets.clear()
        release()
    }

    /** Throws everything away (session ended); the jitter estimate is kept. */
    fun flush() {
        packets.clear()
        tid = null
        released = false
        nextSeq = null
        started = false
        lastArrivalMs = null
    }

    private fun grow() {
        delayMs = (delayMs + frameMs).coerceAtMost(MAX_DELAY_MS)
    }

    private fun roundUp(ms: Int): Int = (ms + frameMs - 1) / frameMs * frameMs

    companion object {
        const val MIN_DELAY_MS = 60
        const val INITIAL_DELAY_MS = 120
        const val MAX_DELAY_MS = 300
        const val MAX_GAP_FRAMES = 5
        private const val INITIAL_JITTER_MS = 30.0
        private const val JITTER_FACTOR = 4
        private const val JITTER_SMOOTHING = 16
    }
}
