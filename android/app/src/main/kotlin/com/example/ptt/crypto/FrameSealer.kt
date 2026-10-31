package com.example.ptt.crypto

/**
 * Talker side of one transmission (docs/protocol.md section 12.4 rules 2 and 3): seals Opus packets
 * with seq 0, 1, 2, ... under the key for (session, this device, transmission id). A seq is sealed
 * exactly once, never past [AudioFrame.MAX_SEQ]. Sealed frames are kept (ciphertext only; plaintext
 * is never retained) so milestone 5 can resend the stored frame instead of encrypting again.
 *
 * Obtain one from [AudioSession.onFloorGranted]; do not construct it before the grant.
 */
class FrameSealer internal constructor(val transmissionId: String, private var key: ByteArray?) {
    private val frames = ArrayList<ByteArray>()

    /** Number of frames sealed so far (the next seq). */
    @get:Synchronized
    val sealedCount: Int get() = frames.size

    /** True once [AudioFrame.MAX_SEQ] has been used: [seal] will refuse. */
    @get:Synchronized
    val exhausted: Boolean get() = frames.size > AudioFrame.MAX_SEQ

    /**
     * Seals one Opus packet (1 to 1275 bytes) as the next frame and returns the complete wire frame.
     * Throws [ProtocolException] for a bad packet size, past MaxSeq, or after [close].
     */
    @Synchronized
    fun seal(opusPacket: ByteArray): ByteArray {
        val k = key
        ensure(k != null) { "sealer is closed" }
        ensure(opusPacket.size in AudioFrame.MIN_PACKET..AudioFrame.MAX_PACKET) { "opus packet size out of range" }
        ensure(frames.size <= AudioFrame.MAX_SEQ) { "seq limit reached" }
        val seq = frames.size
        val header = AudioFrame.encodeHeader(transmissionId, seq)
        val frame = header + FrameCipher.seal(k, seq, header, opusPacket)
        frames.add(frame.copyOf()) // stored copy: the caller may mutate or reuse the returned array
        return frame
    }

    /** The stored wire frame for [seq], for a milestone-5 resend (never re-encrypted), or null. */
    @Synchronized
    fun stored(seq: Int): ByteArray? = frames.getOrNull(seq)?.copyOf()

    /** Zeroes the key; later [seal] calls fail. */
    @Synchronized
    fun close() {
        key?.fill(0)
        key = null
    }

    /** Test hook: true once the key has been wiped. */
    @get:Synchronized
    internal val isClosed: Boolean get() = key == null
}
