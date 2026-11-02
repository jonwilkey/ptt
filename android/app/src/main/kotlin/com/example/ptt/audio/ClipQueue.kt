package com.example.ptt.audio

import com.example.ptt.crypto.AudioFrame
import java.util.BitSet
import java.util.TreeMap

/** What the [ClipQueue] wants played next. */
sealed interface ClipOutput {
    /** The next packet of [transmissionId], due now. */
    class Packet(val transmissionId: String, val seq: Int, val opus: ByteArray) : ClipOutput

    /** [transmissionId] has played out: its decoder can go. */
    class Done(val transmissionId: String) : ClipOutput
}

/**
 * The incoming audio that waits its turn (docs/protocol.md section 13.8): clips, in the order of their `clip`
 * messages, and live transmissions held behind them or behind this phone's own talking. Each entry plays from
 * memory, in seq order, one packet per frame, and is discarded as it plays. Pure Kotlin, single-threaded (the
 * playout thread owns it), time passed in as `now`.
 *
 * A live transmission is held from its first frame, not its grant: the server relays a transmission live only once
 * every transmission granted before it has been delivered (section 13.4), so a `clip` arriving between a grant and
 * its first frame belongs before it. Placing it at its first frame keeps grant order, and a later `clip` means the
 * server has moved past it.
 *
 * Model. An entry is one transmission id with the packets that arrived for it, the seq the player is at, its
 * `last_seq` (unknown for a held live transmission until it is clipped) and the seqs accepted before its clip
 * (they will not come again, so the player passes them). An entry is **finished** when the next `clip` arrives,
 * when its live transmission is released, or after [FINISH_AFTER_MS] without a frame; until then the player waits
 * for a missing seq, from then on it skips the seqs it is missing and moves on after the last one it holds. A
 * live grant does not finish anything. While this phone is [busy] (talking) nothing plays; entries keep filling.
 *
 * A clip for an id whose frames were accepted before it and that lacks fewer than [SHORT_TAIL] of its seqs
 * 0 .. `last_seq` is not played (the tail of an almost fully heard transmission would be a blip); a clip of an id
 * with nothing accepted always plays. Frames of an id that played out or was skipped are dropped.
 */
class ClipQueue(private val frameMs: Int = AudioSpec.FRAME_MS) {
    private class Entry(val id: String, var lastSeq: Int, var acceptedBefore: BitSet, now: Long) {
        val packets = TreeMap<Int, ByteArray>()
        var finished = false
        var lastFrameMs = now
        var cursor = 0

        /** When the packet at [cursor] is due; null until playing starts, and after a stall. */
        var nextSlotMs: Long? = null
    }

    private val entries = ArrayDeque<Entry>()
    private val over = HashSet<String>()
    private var busy = false

    /** True when nothing waits or plays. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** This phone is capturing or sending: nothing plays until it stops. */
    fun busy(on: Boolean) {
        if (busy && !on) entries.firstOrNull()?.nextSlotMs = null
        busy = on
    }

    /** An accepted `clip` for [id]; [acceptedBefore] are the seqs accepted before it. */
    fun clip(id: String, lastSeq: Int, acceptedBefore: BitSet, now: Long) {
        for (e in entries) if (e.id != id) e.finished = true
        val existing = entries.firstOrNull { it.id == id }
        if (existing != null) {
            existing.lastSeq = lastSeq
            existing.acceptedBefore = acceptedBefore
            existing.finished = false
            existing.lastFrameMs = now
            return
        }
        if (isShortTail(lastSeq, acceptedBefore)) {
            over += id
            return
        }
        over -= id
        entries.addLast(Entry(id, lastSeq, acceptedBefore, now))
    }

    /**
     * The first frame of a live transmission arrived. It is held (true: [offer] takes its frames) when an entry
     * already waits or plays, or this phone is busy; otherwise (false) it plays live.
     */
    fun hold(id: String, now: Long): Boolean {
        val held = entries.isNotEmpty() || busy
        if (held && entries.none { it.id == id }) entries.addLast(Entry(id, AudioFrame.MAX_SEQ, BitSet(), now))
        return held
    }

    /** The live transmission [id] was released: if it is held, it is finished. */
    fun liveEnded(id: String) {
        entries.firstOrNull { it.id == id }?.finished = true
    }

    /** Takes a packet if its id belongs here (true); false means it is not a clip or held transmission. */
    fun offer(id: String, seq: Int, opus: ByteArray, now: Long): Boolean {
        if (id in over) return true
        val e = entries.firstOrNull { it.id == id }
        if (e != null && seq in e.cursor..e.lastSeq) {
            e.packets.putIfAbsent(seq, opus)
            e.lastFrameMs = now
        }
        return e != null
    }

    /** The next output due at [now], or null. Call repeatedly until null. */
    @Suppress("ReturnCount")
    fun poll(now: Long): ClipOutput? {
        for (e in entries) if (now - e.lastFrameMs >= FINISH_AFTER_MS) e.finished = true
        if (busy) return null
        val e = entries.firstOrNull() ?: return null
        when (seek(e)) {
            Seek.PLAY -> Unit
            Seek.WAIT -> return null
            Seek.DONE -> return done(e)
        }
        val slot = e.nextSlotMs ?: (now + PREBUFFER_MS).also { e.nextSlotMs = it }
        if (now < slot) return null
        val seq = e.cursor
        val opus = e.packets.remove(seq) ?: return null
        e.cursor = seq + 1
        e.nextSlotMs = slot + frameMs
        return ClipOutput.Packet(e.id, seq, opus)
    }

    private enum class Seek { PLAY, WAIT, DONE }

    /** Moves [e]'s cursor past the seqs that will not come (or, once finished, are missing) to one it holds. */
    @Suppress("ReturnCount")
    private fun seek(e: Entry): Seek {
        while (e.packets[e.cursor] == null) {
            val next = when {
                e.cursor > e.lastSeq -> return Seek.DONE

                e.acceptedBefore.get(e.cursor) -> e.cursor + 1

                e.finished -> e.packets.ceilingKey(e.cursor) ?: return Seek.DONE

                else -> {
                    e.nextSlotMs = null
                    return Seek.WAIT
                }
            }
            e.cursor = next
        }
        return Seek.PLAY
    }

    /** Drops the entry that plays now (audio focus was lost); the rest of its frames are dropped as they come. */
    fun dropHead() {
        entries.removeFirstOrNull()?.let { over += it.id }
    }

    /** Session over: everything goes. */
    fun clear() {
        entries.clear()
        over.clear()
        busy = false
    }

    private fun done(e: Entry): ClipOutput.Done {
        entries.remove(e)
        over += e.id
        return ClipOutput.Done(e.id)
    }

    companion object {
        /** A clip without a frame for this long is finished (section 13.8). */
        const val FINISH_AFTER_MS = 3_000L

        /** Fewer missing seqs than this in a partly heard transmission: its clip is not played (section 13.8). */
        const val SHORT_TAIL = 10

        /** How far ahead of its first packet an entry starts (and restarts after waiting for a frame). */
        const val PREBUFFER_MS = 120L
    }
}

/** Something of the id was accepted, and fewer than [ClipQueue.SHORT_TAIL] of 0 .. [lastSeq] are missing. */
private fun isShortTail(lastSeq: Int, acceptedBefore: BitSet): Boolean {
    val missing = lastSeq + 1 - acceptedBefore.get(0, lastSeq + 1).cardinality()
    return !acceptedBefore.isEmpty && missing < ClipQueue.SHORT_TAIL
}
