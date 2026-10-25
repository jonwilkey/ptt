package com.example.ptt.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.signature.Ed25519PublicKey
import java.security.GeneralSecurityException

/** The two private keysets of a device. */
class KeysetPair(val signing: KeysetHandle, val hpke: KeysetHandle)

/**
 * The device's keys cannot be used: a keyset is missing, the Android Keystore master key is gone or
 * unusable, or the stored keyset would not be protected by the Keystore. The caller decides what to do:
 * a Keystore error can be transient (boot, an OTA), so retry first; if the keys stay unavailable they are
 * not recoverable, and the app should wipe its local state (including the roster and device token)
 * and re-enroll. [DeviceKeyStore.load] never erases anything because of such an error.
 */
class DeviceKeysUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where the device's two private keysets live. The production implementation is
 * [AndroidDeviceKeyStore] (Tink keysets encrypted by Android Keystore keys); JVM unit tests supply an
 * in-memory one because Robolectric has no AndroidKeyStore.
 *
 * Generation and loading are separate on purpose: only enrollment may mint keys ([create]); every
 * other start-up path calls [load], which never generates, so a lost keyset surfaces as
 * [DeviceKeysUnavailable] instead of silently becoming a new, unenrolled identity.
 */
interface DeviceKeyStore {
    /**
     * Generates and persists a fresh Ed25519 signing keyset (RAW prefix) and HPKE keyset (X25519,
     * HKDF-SHA256, ChaCha20-Poly1305, RAW prefix). For enrollment only. Fails with
     * [IllegalStateException] if keysets already exist (call [wipe] first when re-enrolling) and with
     * [DeviceKeysUnavailable] if they cannot be protected by the Keystore.
     */
    fun create(): KeysetPair

    /** Loads the existing keysets. Never generates; throws [DeviceKeysUnavailable] if they are unusable. */
    fun load(): KeysetPair

    /** Deletes both keysets (and their Keystore keys), e.g. before re-enrolling. */
    fun wipe()
}

/**
 * This device's keys: signs with Ed25519 and unwraps HPKE-wrapped session keys, and exposes the
 * raw 32-byte public keys that go into the roster. Private key material never leaves the store.
 */
class DeviceKeys private constructor(pair: KeysetPair) {
    private val signingKeyset = pair.signing
    private val hpkeKeyset = pair.hpke
    private val signer = TinkKeys.signer(signingKeyset)
    private val decrypter = TinkKeys.decrypter(hpkeKeyset)

    /** Raw 32-byte Ed25519 public key. */
    val ed25519Public: ByteArray = rawPublic(signingKeyset) { (it as Ed25519PublicKey).publicKeyBytes.toByteArray() }

    /** Raw 32-byte X25519 public key. */
    val x25519Public: ByteArray = rawPublic(hpkeKeyset) { (it as HpkePublicKey).publicKeyBytes.toByteArray() }

    /** Signs `ctx || 0x00 || payload` and returns the 64-byte signature. */
    fun sign(ctx: String, payload: ByteArray): ByteArray = signer.sign(SignedObject.message(ctx, payload))

    /** Signs [payload] under [ctx] as device [signerId]. */
    fun signObject(ctx: String, signerId: String, payload: ByteArray): SignedObject =
        SignedObject(payload, signerId, sign(ctx, payload))

    /**
     * Opens an HPKE-wrapped 32-byte key (enc || ct) with the given `info`. Throws
     * [ProtocolException] on any failure, without saying why.
     */
    fun unwrap(wrapped: ByteArray, info: ByteArray): ByteArray {
        val key = try {
            decrypter.decrypt(wrapped, info)
        } catch (_: GeneralSecurityException) {
            protocolError("could not open the wrapped key")
        }
        ensure(key.size == Validation.KEY_BYTES) { "wrapped key has the wrong size" }
        return key
    }

    private fun rawPublic(keyset: KeysetHandle, extract: (Any) -> ByteArray): ByteArray {
        val publicKeyset = keyset.publicKeysetHandle
        ensure(publicKeyset.size() == 1) { "a device keyset must hold exactly one key" }
        val raw = extract(publicKeyset.getAt(0).key)
        ensure(raw.size == Validation.KEY_BYTES) { "public key is not 32 bytes" }
        return raw
    }

    companion object {
        /** New keys for enrollment ([DeviceKeyStore.create]). */
        fun create(store: DeviceKeyStore): DeviceKeys = DeviceKeys(store.create())

        /** The enrolled device's existing keys ([DeviceKeyStore.load]); never generates. */
        fun load(store: DeviceKeyStore): DeviceKeys = DeviceKeys(store.load())
    }
}
