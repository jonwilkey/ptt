package com.example.ptt.crypto

/**
 * Strict unpadded base64url (RFC 4648 section 5), as docs/protocol.md section 1 requires. Rejects
 * padding, the standard alphabet, whitespace, and non-zero trailing bits, like Go's
 * `base64.RawURLEncoding.Strict()`. (java.util.Base64 would accept several of those.)
 */
object B64u {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val BITS_PER_CHAR = 6
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val QUANTUM = 4
    private const val ASCII = 128
    private val reverse = IntArray(ASCII) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * BYTE_BITS + BITS_PER_CHAR - 1) / BITS_PER_CHAR)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl BYTE_BITS) or (b.toInt() and BYTE_MASK)
            bits += BYTE_BITS
            while (bits >= BITS_PER_CHAR) {
                bits -= BITS_PER_CHAR
                out.append(ALPHABET[(buffer shr bits) and (ASCII / 2 - 1)])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and (ASCII / 2 - 1)])
        return out.toString()
    }

    /** Decodes [text] strictly; throws [ProtocolException] for anything but canonical b64u. */
    fun decode(text: String): ByteArray {
        ensure(text.length % QUANTUM != 1) { "b64u: impossible length" }
        val out = ByteArray(text.length * BITS_PER_CHAR / BYTE_BITS)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in text) {
            val value = if (c.code < ASCII) reverse[c.code] else -1
            ensure(value >= 0) { "b64u: character outside the alphabet" }
            buffer = (buffer shl BITS_PER_CHAR) or value
            bits += BITS_PER_CHAR
            if (bits >= BYTE_BITS) {
                bits -= BYTE_BITS
                out[index++] = ((buffer shr bits) and BYTE_MASK).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        ensure(buffer == 0) { "b64u: non-zero trailing bits" }
        return out
    }

    /** Like [decode] but also requires exactly [size] decoded bytes. */
    fun decodeExact(text: String, size: Int, what: String): ByteArray {
        val raw = decode(text)
        ensure(raw.size == size) { "$what must be $size bytes" }
        return raw
    }
}
