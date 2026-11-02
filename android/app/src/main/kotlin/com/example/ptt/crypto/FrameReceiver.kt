package com.example.ptt.crypto

import java.util.BitSet

/** Verdict on a `floor_granted` (docs/protocol.md sections 11.6 and 12.4 rule 4). */
enum class GrantVerdict {
    /** A new transmission is current. */
    ACCEPTED,

    /** The same talker and id as the current (or suspended) transmission: its accepted-seq set is kept. */
    RESUMED,

    /** Refused by rule 4 (repeated id, other talker for an id in use, or a grant while one is current). */
    REFUSED,

    /** The talker is not the peer this phone expects (section 11.6): the grant is ignored entirely. */
    IGNORED
}

/** Verdict on one binary frame (docs/protocol.md section 12.5). */
sealed interface FrameVerdict {
    /**
     * Authenticated and first time for its seq: hand [opus] to the player. [heard] is the `last_seq` of a `heard`
     * to send now for [transmissionId] (section 13.5 triggers 2 and 3), or null.
     */
    class Accepted(val opus: ByteArray, val transmissionId: String, val seq: Int, val heard: Int? = null) :
        FrameVerdict

    /** Authenticated, but the seq was already accepted for this transmission. */
    data object Replay : FrameVerdict

    /** Failed a section 12.5 check; nothing was recorded. */
    class Dropped(val reason: FrameDropReason) : FrameVerdict
}

/** Verdict on a `clip {talker, transmission_id, last_seq}` (docs/protocol.md sections 13.7 and 13.8). */
sealed interface ClipVerdict {
    /**
     * Accepted: frames of the id up to its `last_seq` now pass section 12.5 step 4. [endedCurrent]: it was the
     * current or suspended transmission, which now counts as released (the floor is free). [acceptedBefore] holds
     * the seqs accepted before the clip (they will not play again). [heard] is the `last_seq` of a `heard` to send
     * now (section 13.5 trigger 1, or trigger 2 when k already reaches the clip's `last_seq`), or null.
     */
    class Accepted(val endedCurrent: Boolean, val acceptedBefore: BitSet, val heard: Int?) : ClipVerdict

    /** Refused: it changes nothing, and frames of its id are judged as if it never arrived. */
    data object Refused : ClipVerdict
}

/**
 * Listener side, one per session (docs/protocol.md sections 11.6, 12.4 rules 4 and 5, 12.5, and for protocol 2
 * sections 13.5 and 13.8). For every transmission id seen in the session it keeps the talker, the set of accepted
 * seqs (recorded only after the tag verifies), the status (current, suspended or released) and the `last_seq` of
 * an accepted clip. Rule 4 judges every grant, the phone's own ones too ([onOwnGranted]), so a transmission of
 * this phone holds the floor like the peer's; its frames are never opened and it never gets a `heard`.
 *
 * Only grants for the expected peer reach rule 4: [expectedPeer] is the envelope's `from_device` for the answerer,
 * and the `peer_joined` (or first accepted `clip`) device that is in the envelope's `keys` for the sender (set it
 * with [setExpectedPeer]). Which `clip.talker` is acceptable is [AudioSession]'s check. Thread-safe.
 */
@Suppress("TooManyFunctions")
class FrameReceiver internal constructor(sessionKey: ByteArray, private val sessionId: String, expectedPeer: String?) {
    private enum class Status { CURRENT, SUSPENDED, RELEASED }

    /** One transmission id seen in the session; [key] is null for this phone's own transmissions. */
    private class Transmission(val id: String, val talker: String, val key: ByteArray?, val granted: Boolean) {
        val accepted = BitSet()
        var status = Status.CURRENT
        var clipLastSeq = NONE

        /** k of section 13.5: every seq 0 .. k is accepted. */
        var prefix = NONE

        /** The `last_seq` of the last `heard` reported, or [NONE]. */
        var heardSent = NONE
    }

    private val sessionKey: ByteArray = sessionKey.copyOf()
    private val seen = HashMap<String, Transmission>()
    private var current: Transmission? = null
    private var peer: String? = expectedPeer
    private var closed = false

    init {
        FrameCipher.requireUsableKey(sessionKey)
        Validation.requireUuid(sessionId, "session_id")
    }

    /** Sets the only talker whose grants are considered (the sender learns it from `peer_joined` or a `clip`). */
    @Synchronized
    fun setExpectedPeer(deviceId: String) {
        Validation.requireUuid(deviceId, "peer device id")
        peer = deviceId
    }

    /**
     * Applies `floor_granted {talker, transmission_id}` for a talker other than this phone.
     *
     * Deliberate difference from the Go model: a grant for a talker that is not the expected peer is
     * [GrantVerdict.IGNORED] before rule 4, so it does not release a suspended transmission.
     */
    @Synchronized
    fun onGranted(talker: String, transmissionId: String): GrantVerdict {
        val valid = Validation.isUuid(talker) && Validation.isUuid(transmissionId)
        if (closed || !valid || talker != peer) return GrantVerdict.IGNORED
        return grant(talker, transmissionId, own = false)
    }

    /** Rule 4 for a grant that answers this phone's own request ([ownDeviceId] is the talker). */
    @Synchronized
    fun onOwnGranted(ownDeviceId: String, transmissionId: String): GrantVerdict {
        if (closed || !Validation.isUuid(transmissionId)) return GrantVerdict.IGNORED
        return grant(ownDeviceId, transmissionId, own = true)
    }

    @Suppress("ReturnCount")
    private fun grant(talker: String, transmissionId: String, own: Boolean): GrantVerdict {
        val cur = current
        if (cur != null && cur.status == Status.SUSPENDED) {
            if (cur.talker == talker && cur.id == transmissionId) {
                cur.status = Status.CURRENT
                return GrantVerdict.RESUMED
            }
            // treated as released while we were away; its id stays seen, hence refused forever
            release(cur)
        }
        val live = current
        if (live != null) {
            val same = live.talker == talker && live.id == transmissionId
            return if (same) GrantVerdict.RESUMED else GrantVerdict.REFUSED
        }
        if (transmissionId in seen) return GrantVerdict.REFUSED
        val key = if (own) null else FrameCipher.deriveKey(sessionKey, sessionId, talker, transmissionId)
        val t = Transmission(transmissionId, talker, key, granted = true)
        seen[transmissionId] = t
        current = t
        return GrantVerdict.ACCEPTED
    }

    /**
     * `floor_released` for [transmissionId]: if it is the current one, it ends and its id is refused forever.
     * Returns the `last_seq` of the `heard` to send (section 13.5 trigger 1), or null.
     */
    @Synchronized
    fun onReleased(transmissionId: String): Int? {
        val t = seen[transmissionId] ?: return null
        if (current === t) release(t)
        return if (t.granted && !closed) report(t) else null
    }

    /** This phone's own socket closed: the current transmission is suspended (section 12.4 rule 4). */
    @Synchronized
    fun onSocketClosed() {
        current?.status = Status.SUSPENDED
    }

    /**
     * Steps 2 to 4 of section 13.8 for a `clip` whose talker [AudioSession] already checked (section 13.7, and
     * step 1: never this phone itself).
     */
    @Synchronized
    @Suppress("ReturnCount")
    fun onClip(talker: String, transmissionId: String, lastSeq: Int): ClipVerdict {
        val valid = Validation.isUuid(talker) && Validation.isUuid(transmissionId) && lastSeq in 0..AudioFrame.MAX_SEQ
        if (closed || !valid) return ClipVerdict.Refused
        val known = seen[transmissionId]
        if (known != null && (known.talker != talker || known.key == null)) return ClipVerdict.Refused
        val t = known ?: Transmission(
            transmissionId,
            talker,
            FrameCipher.deriveKey(sessionKey, sessionId, talker, transmissionId),
            granted = false
        ).also {
            it.status = Status.RELEASED
            seen[transmissionId] = it
        }
        val endedCurrent = current === t
        if (endedCurrent) release(t)
        t.clipLastSeq = lastSeq
        val heard = if ((endedCurrent && t.granted) || t.prefix >= lastSeq) report(t) else null
        return ClipVerdict.Accepted(endedCurrent, t.accepted.clone() as BitSet, heard)
    }

    /** Runs the section 12.5 checks (step 4 as amended by section 13.8) and returns the packet or the reason. */
    @Synchronized
    @Suppress("ReturnCount")
    fun onFrame(frame: ByteArray): FrameVerdict {
        val header = when (val parsed = AudioFrame.parse(frame)) {
            is HeaderResult.Rejected -> return FrameVerdict.Dropped(parsed.reason)
            is HeaderResult.Parsed -> parsed.header
        }
        val t = seen[header.transmissionId]
        val cur = current
        val byClip = t != null && header.seq <= t.clipLastSeq
        val early = when {
            closed -> FrameDropReason.NO_TRANSMISSION
            byClip -> null
            cur == null -> FrameDropReason.NO_TRANSMISSION
            cur.status == Status.SUSPENDED -> FrameDropReason.SUSPENDED
            header.transmissionId != cur.id -> FrameDropReason.WRONG_TRANSMISSION
            else -> null
        }
        if (early != null) return FrameVerdict.Dropped(early)
        // A frame is never played when the talker is this phone itself (section 12.5).
        val key = t?.key ?: return FrameVerdict.Dropped(FrameDropReason.WRONG_TRANSMISSION)
        val aad = frame.copyOfRange(0, AudioFrame.HEADER_SIZE)
        val sealed = frame.copyOfRange(AudioFrame.HEADER_SIZE, frame.size)
        val opus = FrameCipher.open(key, header.seq, aad, sealed)
            ?: return FrameVerdict.Dropped(FrameDropReason.DECRYPT_FAILED)
        if (t.accepted.get(header.seq)) {
            opus.fill(0)
            return FrameVerdict.Replay
        }
        t.accepted.set(header.seq)
        return FrameVerdict.Accepted(opus, header.transmissionId, header.seq, heardAfterAccepting(t))
    }

    /** Section 13.5 triggers 2 (k reaches the clip's `last_seq`) and 3 (k grew by [HEARD_STEP] since the last). */
    private fun heardAfterAccepting(t: Transmission): Int? {
        val before = t.prefix
        while (t.accepted.get(t.prefix + 1)) t.prefix++
        if (t.prefix == before) return null
        val reachedClipEnd = t.prefix == t.clipLastSeq
        val stepped = t.prefix - t.heardSent >= HEARD_STEP
        return if (reachedClipEnd || stepped) report(t) else null
    }

    /** The `heard` for [t] now: never for this phone's own transmission, never before seq 0 was accepted. */
    private fun report(t: Transmission): Int? {
        if (t.key == null || t.prefix < 0) return null
        t.heardSent = t.prefix
        return t.prefix
    }

    /** The `last_seq` a `heard` for [transmissionId] would carry now, or null when none may be sent. */
    @Synchronized
    internal fun heardValue(transmissionId: String): Int? {
        val t = seen[transmissionId] ?: return null
        return if (t.key == null || t.prefix < 0) null else t.prefix
    }

    /** Zeroes all keys and forgets all state. */
    @Synchronized
    fun close() {
        closed = true
        current = null
        for (t in seen.values) t.key?.fill(0)
        seen.clear()
        sessionKey.fill(0)
    }

    @get:Synchronized
    internal val isClosed: Boolean get() = closed && sessionKey.all { it == 0.toByte() }

    private fun release(t: Transmission) {
        t.status = Status.RELEASED
        if (current === t) current = null
    }

    private companion object {
        const val NONE = -1

        /** 250 frames: 5 s of audio (section 13.5 trigger 3). */
        const val HEARD_STEP = 250
    }
}
