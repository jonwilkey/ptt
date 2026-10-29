package com.example.ptt.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.util.Bytes
import java.security.GeneralSecurityException

/** Tink parameter sets and raw-key adapters for the two device key types (docs/protocol.md section 2). */
object TinkKeys {
    /** Ed25519 with the RAW output prefix, so signatures are the plain 64 bytes of RFC 8032. */
    val ed25519Parameters: Ed25519Parameters = Ed25519Parameters.create(Ed25519Parameters.Variant.NO_PREFIX)

    /** DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305 with the RAW prefix: enc || ct. */
    val hpkeParameters: HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    private val registered: Unit by lazy {
        SignatureConfig.register()
        HybridConfig.register()
    }

    /**
     * Registers the Tink key types this app uses. Idempotent, and it must run before any keyset is parsed or
     * created: a keyset parsed first comes back with [com.google.crypto.tink.internal.LegacyProtoKey] entries
     * instead of the real key classes. So every path that builds a [KeysetHandle] (the key store's create and
     * load, `Application.onCreate`) calls this first, instead of relying on when this object happens to initialise.
     */
    fun register(): Unit = registered

    // Also on first use of this object, as a backstop; the callers above are what guarantee the order.
    // The lazy `registered` above must stay declared before this block (initialisers run in order).
    init {
        register()
    }

    /** Verifies [sig] over [message] with the raw 32-byte public key; false for any failure. */
    fun verifyEd25519(publicKey: ByteArray, message: ByteArray, sig: ByteArray): Boolean = try {
        val key = Ed25519PublicKey.create(
            Ed25519Parameters.Variant.NO_PREFIX,
            Bytes.copyFrom(publicKey),
            null
        )
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        handle.getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java).verify(sig, message)
        true
    } catch (_: GeneralSecurityException) {
        false
    }

    /** An HPKE encrypter to the raw 32-byte X25519 public key. */
    fun hpkeEncrypter(publicKey: ByteArray): HybridEncrypt {
        val key = HpkePublicKey.create(hpkeParameters, Bytes.copyFrom(publicKey), null)
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
    }

    internal fun signer(keyset: KeysetHandle): PublicKeySign =
        keyset.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)

    internal fun decrypter(keyset: KeysetHandle): HybridDecrypt =
        keyset.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
}
