package com.example.ptt.crypto

import java.nio.ByteBuffer
import java.util.UUID

/** Why a received audio frame was dropped, in the names of docs/protocol.md section 12.5 and the test vectors. */
enum class FrameDropReason(val wireName: String) {
    BAD_LENGTH("bad_length"),
    BAD_TYPE("bad_type"),
    BAD_SEQ("bad_seq"),
    WRONG_TRANSMISSION("wrong_transmission"),
    DECRYPT_FAILED("decrypt_failed"),

    /** No transmission is current (nothing granted, or it was released). */
    NO_TRANSMISSION("no_transmission"),

    /** The current transmission is suspended because this socket closed (section 12.4). */
    SUSPENDED("suspended")
}

/** The 21-byte frame header of docs/protocol.md section 12.1: type, transmission id, seq. */
class FrameHeader(val transmissionId: String, val seq: Int)

/** Outcome of [AudioFrame.parse]: a [Parsed] header or the first failing check of section 12.5. */
sealed interface HeaderResult {
    class Parsed(val header: FrameHeader) : HeaderResult
    class Rejected(val reason: FrameDropReason) : HeaderResult
}

/** Wire layout of a live audio frame (docs/protocol.md section 12.1); matches the server's `audioframe`. */
object AudioFrame {
    const val TYPE_LIVE: Int = 0x01
    const val HEADER_SIZE = 21
    const val TAG_SIZE = 16
    const val MIN_PACKET = 1
    const val MAX_PACKET = 1275
    const val MIN_FRAME = HEADER_SIZE + MIN_PACKET + TAG_SIZE
    const val MAX_FRAME = HEADER_SIZE + MAX_PACKET + TAG_SIZE
    const val MAX_SEQ = 4095

    private const val UUID_BYTES = 16
    private const val ID_OFFSET = 1
    private const val SEQ_OFFSET = ID_OFFSET + UUID_BYTES
    private const val HEX_RADIX = 16

    /** Encodes the header: type 0x01, the raw UUID bytes (msb then lsb), then seq as uint32 big-endian. */
    fun encodeHeader(transmissionId: String, seq: Int): ByteArray {
        Validation.requireUuid(transmissionId, "transmission_id")
        ensure(seq in 0..MAX_SEQ) { "seq out of range" }
        val buf = ByteBuffer.allocate(HEADER_SIZE)
        buf.put(TYPE_LIVE.toByte())
        buf.put(uuidToBytes(transmissionId))
        buf.putInt(seq)
        return buf.array()
    }

    /** Applies checks 1 to 3 of section 12.5 (length, type, seq bound) and reads the header. */
    @Suppress("ReturnCount")
    fun parse(frame: ByteArray): HeaderResult {
        val reason = when {
            frame.size < MIN_FRAME || frame.size > MAX_FRAME -> FrameDropReason.BAD_LENGTH
            frame[0].toInt() != TYPE_LIVE -> FrameDropReason.BAD_TYPE
            else -> null
        }
        if (reason != null) return HeaderResult.Rejected(reason)
        val seq = ByteBuffer.wrap(frame, SEQ_OFFSET, Int.SIZE_BYTES).int
        if (seq < 0 || seq > MAX_SEQ) return HeaderResult.Rejected(FrameDropReason.BAD_SEQ)
        return HeaderResult.Parsed(FrameHeader(bytesToUuid(frame, ID_OFFSET), seq))
    }

    internal fun uuidToBytes(uuid: String): ByteArray {
        val hex = uuid.replace("-", "")
        return ByteArray(UUID_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
    }

    internal fun bytesToUuid(buf: ByteArray, offset: Int): String {
        val bb = ByteBuffer.wrap(buf, offset, UUID_BYTES)
        return UUID(bb.long, bb.long).toString() // lowercase canonical text
    }
}
