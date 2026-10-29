package com.example.ptt.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression test for "every roster sync fails after an app restart": in a fresh process the key store
 * parses the stored keysets before [TinkKeys] has registered the key types, so Tink returned
 * `LegacyProtoKey` and `DeviceKeys` threw ClassCastException. Tink's registry is JVM-global (other tests
 * have already registered it here), so the check runs in a child JVM that starts with it empty.
 *
 * What it cannot run is `AndroidDeviceKeyStore` itself (no AndroidKeyStore on the JVM); that class calls
 * [TinkKeys.register] as the first line of create() and load().
 */
class TinkRegistrationOrderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(handle: KeysetHandle, name: String): File = File(tmp.root, name).also {
        it.writeBytes(
            TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        )
    }

    @Test
    fun keysetsParsedInAFreshProcessLoadOnceRegistrationRunsFirst() {
        val pair = InMemoryDeviceKeyStore().create()
        val signing = write(pair.signing, "signing.json")
        val hpke = write(pair.hpke, "hpke.json")
        val log = File(tmp.root, "probe.log")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            TinkRegistrationProbe::class.java.name,
            signing.path,
            hpke.path
        ).redirectErrorStream(true).redirectOutput(log).start()
        if (!child.waitFor(60, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            fail("child JVM timed out: " + log.readText())
        }
        assertEquals(log.readText(), 0, child.exitValue())
    }
}
