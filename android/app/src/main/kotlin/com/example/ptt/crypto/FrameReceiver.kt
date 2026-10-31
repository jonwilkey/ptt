package com.example.ptt.crypto

import java.util.BitSet

/** Verdict on a `floor_granted` naming another talker (docs/protocol.md sections 11.6 and 12.4 rule 4). */
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
    /** Authenticated and first time for its seq: hand [opus] to the jitter buffer. */
    class Accepted(val opus: ByteArray) : FrameVerdict

    /** Authenticated, but the seq was already accepted for this transmission. */
    data object Replay : FrameVerdict

    /** Failed a section 12.5 check; nothing was recorded. */
    class Dropped(val reason: FrameDropReason) : FrameVerdict
}

/**
 * Listener side, one per session (docs/protocol.md sections 11.6, 12.4 rules 4 and 5, 12.5). Tracks
 * the current transmission, the ids seen, suspension across the receiver's own socket closing, and
 * the per-transmission set of accepted seqs (recorded only after the tag verifies).
 *
 * Only grants for the expected peer reach rule 4: [expectedPeer] is the envelope's `from_device`
 * for the answerer, and the `peer_joined` device that is in the envelope's `keys` for the sender
 * (set it with [AudioSession.onPeerJoined]). Grants naming this phone itself are handled by
 * [TalkerFloor], not here. Thread-safe.
 */
class FrameReceiver internal constructor(sessionKey: ByteArray, private val sessionId: String, expectedPeer: String?) {
    private class Transmission(val id: String, val talker: String, val key: ByteArray) {
        val accepted = BitSet(AudioFrame.MAX_SEQ + 1)
        var suspended = false
    }

    private val sessionKey: ByteArray = sessionKey.copyOf()
    private val seen = HashSet<String>()
    private var current: Transmission? = null
    private var peer: String? = expectedPeer
    private var closed = false

    init {
        FrameCipher.requireUsableKey(sessionKey)
        Validation.requireUuid(sessionId, "session_id")
    }

    /** Sets the only talker whose grants are considered (the sender learns it from `peer_joined`). */
    @Synchronized
    fun setExpectedPeer(deviceId: String) {
        Validation.requireUuid(deviceId, "peer device id")
        peer = deviceId
    }

    /** The transmission being played, if any (also while suspended). */
    @get:Synchronized
    val currentTransmission: String? get() = current?.id

    /**
     * Applies `floor_granted {talker, transmission_id}` for a talker other than this phone.
     *
     * Deliberate difference from the Go model: a grant for a talker that is not the expected peer is
     * [GrantVerdict.IGNORED] before rule 4, so it does not release a suspended transmission.
     */
    @Synchronized
    @Suppress("ReturnCount")
    fun onGranted(talker: String, transmissionId: String): GrantVerdict {
        val valid = Validation.isUuid(talker) && Validation.isUuid(transmissionId)
        if (closed || !valid || talker != peer) return GrantVerdict.IGNORED
        val cur = current
        if (cur != null && cur.suspended) {
            if (cur.talker == talker && cur.id == transmissionId) {
                cur.suspended = false
                return GrantVerdict.RESUMED
            }
            // treated as released while we were away; its id stays seen, hence refused forever
            drop()
        }
        val live = current
        if (live != null) {
            val same = live.talker == talker && live.id == transmissionId
            return if (same) GrantVerdict.RESUMED else GrantVerdict.REFUSED
        }
        if (transmissionId in seen) return GrantVerdict.REFUSED
        seen.add(transmissionId)
        current = Transmission(
            transmissionId,
            talker,
            FrameCipher.deriveKey(sessionKey, sessionId, talker, transmissionId)
        )
        return GrantVerdict.ACCEPTED
    }

    /** `floor_released` for [transmissionId]: if it is the current one, it ends and its id is refused forever. */
    @Synchronized
    fun onReleased(transmissionId: String) {
        if (current?.id == transmissionId) drop()
    }

    /** This phone's own socket closed: the current transmission is suspended (section 12.4 rule 4). */
    @Synchronized
    fun onSocketClosed() {
        current?.suspended = true
    }

    /** Runs the section 12.5 checks in order and returns the decrypted Opus packet or the reason for dropping. */
    @Synchronized
    @Suppress("ReturnCount")
    fun onFrame(frame: ByteArray): FrameVerdict {
        val header = when (val parsed = AudioFrame.parse(frame)) {
            is HeaderResult.Rejected -> return FrameVerdict.Dropped(parsed.reason)
            is HeaderResult.Parsed -> parsed.header
        }
        val cur = current
        val early = when {
            closed || cur == null -> FrameDropReason.NO_TRANSMISSION
            cur.suspended -> FrameDropReason.SUSPENDED
            header.transmissionId != cur.id -> FrameDropReason.WRONG_TRANSMISSION
            else -> null
        }
        if (early != null || cur == null) return FrameVerdict.Dropped(early ?: FrameDropReason.NO_TRANSMISSION)
        val aad = frame.copyOfRange(0, AudioFrame.HEADER_SIZE)
        val sealed = frame.copyOfRange(AudioFrame.HEADER_SIZE, frame.size)
        val opus = FrameCipher.open(cur.key, header.seq, aad, sealed)
            ?: return FrameVerdict.Dropped(FrameDropReason.DECRYPT_FAILED)
        if (cur.accepted.get(header.seq)) {
            opus.fill(0)
            return FrameVerdict.Replay
        }
        cur.accepted.set(header.seq)
        return FrameVerdict.Accepted(opus)
    }

    /** Zeroes all keys and forgets all state. */
    @Synchronized
    fun close() {
        closed = true
        current?.key?.fill(0)
        current = null
        sessionKey.fill(0)
        seen.clear()
    }

    @get:Synchronized
    internal val isClosed: Boolean get() = closed && sessionKey.all { it == 0.toByte() }

    private fun drop() {
        current?.key?.fill(0)
        current = null
    }
}
