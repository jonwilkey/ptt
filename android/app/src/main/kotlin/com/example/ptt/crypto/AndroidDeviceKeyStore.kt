package com.example.ptt.crypto

import android.annotation.SuppressLint
import android.content.Context
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException

/**
 * Keeps both private keysets in app-private SharedPreferences, each encrypted by its own AES-GCM key
 * in the Android Keystore (`android-keystore://ptt_signing_master` and `ptt_hpke_master`). One master
 * key per keyset keeps the signing key and the HPKE key independently replaceable; neither Keystore
 * key is extractable, so a copy of the preferences file is useless off the device (and backup is off).
 *
 * The spec requires keysets wrapped by a Keystore key. Tink's [AndroidKeysetManager] does not insist:
 * if the master key is unusable it silently falls back to a PLAINTEXT keyset, writing it that way and
 * also accepting cleartext on read. So every open checks `isUsingKeystore`; if false the entry is
 * erased (synchronously) and the call fails with [DeviceKeysUnavailable].
 */
// commit(), not apply(): a keyset entry must be gone from disk before we report failure or return.
// (UseKtx: core-ktx is not a dependency, and edit(commit = true) would be the same call.)
@SuppressLint("ApplySharedPref", "UseKtx")
class AndroidDeviceKeyStore(private val context: Context) : DeviceKeyStore {
    private val prefs get() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun create(): KeysetPair {
        check(KEYSETS.none { prefs.contains(it.name) }) { "device keys already exist" }
        try {
            return KeysetPair(
                open(SIGNING, KeyTemplate.createFrom(TinkKeys.ed25519Parameters), eraseOnError = true),
                open(HPKE, KeyTemplate.createFrom(TinkKeys.hpkeParameters), eraseOnError = true)
            )
        } catch (e: DeviceKeysUnavailable) {
            // Leave nothing half-created, so a retried create() does not trip over "already exist".
            erase(KEYSETS)
            throw e
        }
    }

    override fun load(): KeysetPair {
        for (keyset in KEYSETS) {
            if (!prefs.contains(keyset.name)) throw DeviceKeysUnavailable("a device keyset is missing")
        }
        // A Keystore error here may be transient (boot, OTA), so load() never erases because of one.
        return KeysetPair(
            open(SIGNING, null, eraseOnError = false),
            open(HPKE, null, eraseOnError = false)
        )
    }

    override fun wipe() {
        erase(KEYSETS)
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            KEYSETS.forEach { keyStore.deleteEntry(it.masterAlias) }
        } catch (_: GeneralSecurityException) {
            // The keysets are already gone, so a leftover Keystore key protects nothing.
        } catch (_: IOException) {
            // Same: the wrapped keysets were erased above.
        }
    }

    /**
     * Opens one keyset under its Keystore master key. Tink/Keystore failures become
     * [DeviceKeysUnavailable]; the stored entry is erased for them only when [eraseOnError] (create()).
     * A cleartext fallback (`!isUsingKeystore`) is always erased, in load() too: it must not persist.
     */
    @Suppress("ThrowsCount")
    private fun open(keyset: Keyset, template: KeyTemplate?, eraseOnError: Boolean): KeysetHandle {
        try {
            val builder = AndroidKeysetManager.Builder()
                .withSharedPref(context, keyset.name, PREFS_FILE)
                .withMasterKeyUri("$KEYSTORE_URI_PREFIX${keyset.masterAlias}")
            if (template != null) builder.withKeyTemplate(template)
            val manager = builder.build()
            if (manager.isUsingKeystore) return manager.keysetHandle
        } catch (e: GeneralSecurityException) {
            throw unavailable(keyset, eraseOnError, e)
        } catch (e: IOException) {
            throw unavailable(keyset, eraseOnError, e)
        } catch (e: ProviderException) {
            throw unavailable(keyset, eraseOnError, e)
        } catch (e: NoSuchElementException) {
            throw unavailable(keyset, eraseOnError, e)
        }
        // Tink fell back to a cleartext keyset: never keep one.
        throw unavailable(keyset, true, null)
    }

    private fun unavailable(keyset: Keyset, erase: Boolean, cause: Throwable?): DeviceKeysUnavailable {
        if (erase) erase(listOf(keyset))
        return DeviceKeysUnavailable("device keyset could not be opened under the Android Keystore", cause)
    }

    private fun erase(keysets: List<Keyset>) {
        prefs.edit().apply { keysets.forEach { remove(it.name) } }.commit()
    }

    private class Keyset(val name: String, val masterAlias: String)

    private companion object {
        const val PREFS_FILE = "ptt_device_keys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_URI_PREFIX = "android-keystore://"
        val SIGNING = Keyset("signing_keyset", "ptt_signing_master")
        val HPKE = Keyset("hpke_keyset", "ptt_hpke_master")
        val KEYSETS = listOf(SIGNING, HPKE)
    }
}
