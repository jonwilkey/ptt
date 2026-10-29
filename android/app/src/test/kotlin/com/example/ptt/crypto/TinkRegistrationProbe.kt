package com.example.ptt.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point of the child JVM that [TinkRegistrationOrderTest] starts, so Tink's JVM-global registry is
 * still empty. Arguments: the signing and HPKE keyset JSON files. Exits 0 when every check holds.
 */
object TinkRegistrationProbe {
    // The legacy overload, as AndroidKeysetManager uses; the Configuration overload may not reproduce the bug.
    @Suppress("DEPRECATION")
    private fun parse(path: String): KeysetHandle =
        TinkProtoKeysetFormat.parseKeyset(File(path).readBytes(), InsecureSecretKeyAccess.get())

    private class FixedStore(private val pair: () -> KeysetPair) : DeviceKeyStore {
        override fun create(): KeysetPair = pair()
        override fun load(): KeysetPair = pair()
        override fun wipe() = Unit
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val (signingFile, hpkeFile) = args

        // 1. What AndroidKeysetManager does on a cold start: parse before anything registered the configs.
        val early = KeysetPair(parse(signingFile), parse(hpkeFile))
        // TinkKeys initialises (and registers) only now, as on the phone; the parsed keysets stay legacy.
        TinkKeys.ed25519Parameters
        val earlyKey = early.signing.publicKeysetHandle.getAt(0).key.javaClass.simpleName
        check(earlyKey == "LegacyProtoKey") { "expected LegacyProtoKey before registration, got $earlyKey" }

        // 2. Such a keyset is a clear IllegalStateException (not a ClassCastException, and not "keys unavailable",
        // which would wipe the identity).
        try {
            DeviceKeys.load(FixedStore { early })
            error("a keyset parsed before registration must not load")
        } catch (e: IllegalStateException) {
            check("TinkKeys.register()" in e.message.orEmpty()) { "unhelpful message: ${e.message}" }
        }

        // 3. Registering first (what the key store now does) makes the same files load as real keys.
        TinkKeys.register()
        TinkKeys.register() // idempotent
        val keys = DeviceKeys.load(FixedStore { KeysetPair(parse(signingFile), parse(hpkeFile)) })
        check(keys.ed25519Public.size == 32 && keys.x25519Public.size == 32) { "public keys are not 32 bytes" }
        println("ok")
        exitProcess(0)
    }
}
