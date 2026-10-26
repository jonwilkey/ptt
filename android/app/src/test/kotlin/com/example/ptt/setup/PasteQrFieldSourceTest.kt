package com.example.ptt.setup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The paste-QR field exists only in the debug source set; release gets an inert stub. */
class PasteQrFieldSourceTest {
    private val base = "src/%s/kotlin/com/example/ptt/setup/PasteQrField.kt"

    private fun source(variant: String) = File(base.format(variant)).readText()

    @Test
    fun releaseVariantHasNoTextField() {
        val release = source("release")
        assertFalse(release.contains("TextField"))
        assertFalse(release.contains("onSubmit("))
    }

    @Test
    fun debugVariantHasTheField() {
        assertTrue(source("debug").contains("OutlinedTextField"))
    }

    @Test
    fun mainSourcesNeverDefineIt() {
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains("fun PasteQrField") }
            .toList()
        assertTrue(offenders.toString(), offenders.isEmpty())
    }
}
