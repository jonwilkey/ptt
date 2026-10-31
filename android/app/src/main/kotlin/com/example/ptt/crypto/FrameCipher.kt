package com.example.ptt.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key derivation and AES-256-GCM for audio frames (docs/protocol.md sections 12.2 and 12.3). Pure
 * JVM (`javax.crypto`), no extra dependencies.
 */
internal object FrameCipher {
    private const val LABEL = "ptt-transmission-key-v1"
    private const val HMAC = "HmacSHA256"
    private const val HASH_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * HKDF-SHA256 (RFC 5869) with an empty salt and one output block (L = 32):
     * `info = label || 0x00 || session_id || talker_device_id || transmission_id` (ASCII UUID text).
     * Intermediate key material (PRK, expand input) is zeroed.
     */
    fun deriveKey(sessionKey: ByteArray, sessionId: String, talkerDeviceId: String, transmissionId: String): ByteArray {
        requireUsableKey(sessionKey)
        Validation.requireUuid(sessionId, "session_id")
        Validation.requireUuid(talkerDeviceId, "talker device id")
        Validation.requireUuid(transmissionId, "transmission_id")
        val info = info(sessionId, talkerDeviceId, transmissionId)
        val prk = hmac(ByteArray(HASH_BYTES), sessionKey)
        val expandInput = info + byteArrayOf(1)
        try {
            return hmac(prk, expandInput)
        } finally {
            prk.fill(0)
            expandInput.fill(0)
        }
    }

    /** A session key must be 32 bytes and not all zero (a zeroed, i.e. cleared, key is never usable). */
    fun requireUsableKey(sessionKey: ByteArray) {
        ensure(sessionKey.size == Validation.KEY_BYTES) { "session key must be 32 bytes" }
        ensure(sessionKey.any { it != 0.toByte() }) { "session key is zeroed" }
    }

    internal fun info(sessionId: String, talkerDeviceId: String, transmissionId: String): ByteArray =
        (LABEL + "\u0000" + sessionId + talkerDeviceId + transmissionId).toByteArray(Charsets.US_ASCII)

    /** `8 zero bytes || seq (uint32 big-endian)`; always computed from the header, never sent. */
    fun nonce(seq: Int): ByteArray = ByteBuffer.allocate(NONCE_BYTES).putInt(Int.SIZE_BYTES * 2, seq).array()

    /** Returns `ciphertext || tag`. A fresh Cipher per call, so (key, nonce) reuse is never hidden by state. */
    fun seal(key: ByteArray, seq: Int, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(seq)))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Returns the plaintext, or null if the tag does not verify. */
    fun open(key: ByteArray, seq: Int, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(seq)))
        cipher.updateAAD(aad)
        cipher.doFinal(sealed)
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data)
    }
}
