package com.example.ptt.crypto

/**
 * A signed object (docs/protocol.md section 3): [payload] is the exact signed byte string, [signer]
 * a device id (a lookup key only; the signature does not cover it), [sig] the raw 64-byte Ed25519
 * signature over `ctx || 0x00 || payload`.
 */
class SignedObject(val payload: ByteArray, val signer: String, val sig: ByteArray) {
    /** The wire JSON `{"payload":..,"signer":..,"sig":..}` (key order as in the protocol). */
    fun toWire(): String = """{"payload":"${B64u.encode(payload)}","signer":"$signer","sig":"${B64u.encode(sig)}"}"""

    companion object {
        const val SIGNATURE_BYTES = 64

        /** Strictly decodes wire JSON; throws [ProtocolException] unless it is shaped as section 3 requires. */
        fun fromWire(wire: ByteArray): SignedObject {
            val obj = StrictJson.parse(wire).asObject("signed object").exactly("payload", "signer", "sig")
            val payload = B64u.decode(obj.string("payload"))
            ensure(payload.isNotEmpty()) { "signed object payload is empty" }
            val signer = obj.string("signer")
            Validation.requireUuid(signer, "signer")
            val sig = B64u.decodeExact(obj.string("sig"), SIGNATURE_BYTES, "signature")
            return SignedObject(payload, signer, sig)
        }

        fun fromWire(wire: String): SignedObject = fromWire(wire.toByteArray(Charsets.UTF_8))

        /** The bytes that are signed: `ctx || 0x00 || payload`. */
        internal fun message(ctx: String, payload: ByteArray): ByteArray =
            ctx.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + payload
    }
}
