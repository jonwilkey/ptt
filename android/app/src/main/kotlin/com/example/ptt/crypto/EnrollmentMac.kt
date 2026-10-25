package com.example.ptt.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `HMAC-SHA256(secret, "ptt-enroll-mac-v1" || 0x00 || code(16) || device_id(36 ASCII) || ed25519(32) ||
 * x25519(32))` (docs/protocol.md section 5).
 */
object EnrollmentMac {
    private const val LABEL = "ptt-enroll-mac-v1"
    private const val ALGORITHM = "HmacSHA256"
    const val MAC_BYTES = 32

    fun compute(
        secret: ByteArray,
        code: ByteArray,
        deviceId: String,
        ed25519: ByteArray,
        x25519: ByteArray
    ): ByteArray {
        ensure(secret.size == Validation.KEY_BYTES) { "enrollment secret must be 32 bytes" }
        ensure(code.size == EnrollmentQr.CODE_BYTES) { "enrollment code must be 16 bytes" }
        Validation.requireUuid(deviceId, "device id")
        ensure(ed25519.size == Validation.KEY_BYTES && x25519.size == Validation.KEY_BYTES) { "bad key length" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret, ALGORITHM))
        mac.update(LABEL.toByteArray(Charsets.US_ASCII))
        mac.update(0)
        mac.update(code)
        mac.update(deviceId.toByteArray(Charsets.US_ASCII))
        mac.update(ed25519)
        mac.update(x25519)
        return mac.doFinal()
    }

    /** Constant-time comparison of [mac] with the MAC computed from the other arguments. */
    // The inputs are exactly the MAC's protocol-defined fields plus the MAC under test.
    @Suppress("LongParameterList")
    fun verify(
        secret: ByteArray,
        code: ByteArray,
        deviceId: String,
        ed25519: ByteArray,
        x25519: ByteArray,
        mac: ByteArray
    ): Boolean = MessageDigest.isEqual(compute(secret, code, deviceId, ed25519, x25519), mac)
}
