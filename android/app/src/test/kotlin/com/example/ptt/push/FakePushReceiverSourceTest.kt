package com.example.ptt.push

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake-push receiver may exist only in the debug source set (the release APK must not be able to
 * inject pushes). These check the source layout, which is what decides the merged manifests; the release
 * merged manifest was also inspected when this was written (see android/README.md).
 */
class FakePushReceiverSourceTest {
    private fun file(path: String) = File("src/$path")

    @Test
    fun theReceiverClassExistsOnlyInTheDebugSourceSet() {
        assertTrue(file("debug/kotlin/com/example/ptt/push/FakePushReceiver.kt").isFile)
        val offenders = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.invariantSeparatorsPath.startsWith("src/debug/") }
            .filter { !it.invariantSeparatorsPath.startsWith("src/test/") }
            .filter { it.name == "FakePushReceiver.kt" || it.readText().contains("class FakePushReceiver") }
            .toList()
        assertTrue(offenders.toString(), offenders.isEmpty())
    }

    @Test
    fun onlyTheDebugManifestDeclaresItAndItIsGuardedByDump() {
        val debug = file("debug/AndroidManifest.xml").readText()
        assertTrue(debug.contains("com.example.ptt.push.FakePushReceiver"))
        assertTrue(debug.contains("""android:permission="android.permission.DUMP""""))
        val others = File("src").listFiles().orEmpty()
            .filter { it.name != "debug" && it.name != "test" }
            .map { File(it, "AndroidManifest.xml") }
            .filter { it.isFile }
        assertTrue(others.isNotEmpty())
        for (manifest in others) {
            assertFalse(manifest.path, manifest.readText().contains("FakePushReceiver"))
            assertFalse(manifest.path, manifest.readText().contains("FAKE_PUSH"))
        }
    }
}
