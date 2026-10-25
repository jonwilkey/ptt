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

    init {
        SignatureConfig.register()
        HybridConfig.register()
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
