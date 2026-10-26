package com.example.ptt.net

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device token cannot be stored or read: the Android Keystore key is missing, invalidated or
 * unusable. There is deliberately no plaintext fallback; the caller treats the device as needing
 * re-enrollment (after retrying once, since Keystore errors can be transient at boot).
 */
class DeviceTokenUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where the device token (and the non-secret device id) live. The production implementation is
 * [AndroidDeviceTokenStore]; JVM tests use an in-memory one, because Robolectric has no AndroidKeyStore.
 */
interface DeviceTokenStore {
    /** Stores the token (encrypted) and the device id, replacing any earlier pair. */
    fun save(deviceId: String, token: String)

    /** The token, or null when none is stored. Throws [DeviceTokenUnavailable] if it cannot be decrypted. */
    fun token(): String?

    /** The device id, or null when none is stored. */
    fun deviceId(): String?

    /** Deletes the token and the device id (and the Keystore key). */
    fun clear()
}

/**
 * Stores the token in app-private SharedPreferences, encrypted with an AES-256-GCM key held in the
 * Android Keystore (alias `ptt_token_master`, not extractable). The Keystore key is the only
 * protection: if it cannot be used every call fails with [DeviceTokenUnavailable] and nothing is
 * written in the clear. Backup is off for the app, so the ciphertext never leaves the phone.
 */
// commit(), not apply(): the token must be on disk (or gone) before a call returns.
// (UseKtx: core-ktx is not a dependency.)
@SuppressLint("ApplySharedPref", "UseKtx")
class AndroidDeviceTokenStore(private val context: Context) : DeviceTokenStore {
    private val prefs get() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    @Volatile
    private var cached: String? = null

    @Synchronized
    override fun save(deviceId: String, token: String) {
        val blob = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(deviceId.toByteArray(Charsets.UTF_8))
            val sealed = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            ENCODER.encodeToString(cipher.iv) + SEPARATOR + ENCODER.encodeToString(sealed)
        } catch (e: GeneralSecurityException) {
            throw DeviceTokenUnavailable("could not encrypt the device token", e)
        } catch (e: ProviderException) {
            throw DeviceTokenUnavailable("could not encrypt the device token", e)
        } catch (e: IOException) {
            throw DeviceTokenUnavailable("could not open the Android Keystore", e)
        }
        check(prefs.edit().putString(KEY_DEVICE_ID, deviceId).putString(KEY_TOKEN, blob).commit()) {
            "could not persist the device token"
        }
        cached = token
    }

    @Synchronized
    override fun token(): String? {
        val blob = prefs.getString(KEY_TOKEN, null)
        return cached ?: blob?.let { decrypt(it).also { token -> cached = token } }
    }

    @Suppress("ThrowsCount")
    private fun decrypt(blob: String): String {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: throw DeviceTokenUnavailable("device id is missing")
        val parts = blob.split(SEPARATOR)
        if (parts.size != 2) throw DeviceTokenUnavailable("the stored device token is malformed")
        return try {
            val (iv, sealed) = parts.map { Base64.getDecoder().decode(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(deviceId.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw DeviceTokenUnavailable("could not decrypt the device token", e)
        } catch (e: ProviderException) {
            throw DeviceTokenUnavailable("could not decrypt the device token", e)
        } catch (e: IOException) {
            throw DeviceTokenUnavailable("could not open the Android Keystore", e)
        } catch (e: IllegalArgumentException) {
            throw DeviceTokenUnavailable("the stored device token is malformed", e)
        }
    }

    override fun deviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    @Synchronized
    override fun clear() {
        cached = null
        prefs.edit().remove(KEY_TOKEN).remove(KEY_DEVICE_ID).commit()
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (_: GeneralSecurityException) {
            // The ciphertext is gone, so a leftover Keystore key protects nothing.
        } catch (_: IOException) {
            // Same.
        }
    }

    private fun key(create: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw DeviceTokenUnavailable("the device token key is missing")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_FILE = "ptt_device_token"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE_ID = "device_id"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ptt_token_master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
        val ENCODER: Base64.Encoder = Base64.getEncoder()
    }
}
