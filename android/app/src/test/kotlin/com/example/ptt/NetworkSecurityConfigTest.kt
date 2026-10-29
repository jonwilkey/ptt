package com.example.ptt

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Pins both network security configs (main, and the debug one that replaces it) to what ADR 0009 allows. */
class NetworkSecurityConfigTest {
    private val srcDir = File(requireNotNull(System.getProperty("ptt.appSrcDir")) { "ptt.appSrcDir is not set" })

    private fun config(variant: String): Element {
        val file = File(srcDir, "$variant/res/xml/network_security_config.xml")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.anchors(): List<String> = children("trust-anchors").flatMap { it.children("certificates") }
        .map { it.getAttribute("src") }

    private fun Element.domains(): List<Pair<String, String>> =
        children("domain").map { it.textContent.trim() to it.getAttribute("includeSubdomains") }

    private fun assertShape(variant: String, cleartextHosts: Set<String>) {
        val root = config(variant)
        val base = root.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals(listOf("@raw/isrg_root_x1", "@raw/isrg_root_x2"), base.anchors())

        // Nothing but base-config and domain-config (this excludes debug-overrides).
        val elements = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        assertTrue(elements.all { it.tagName == "base-config" || it.tagName == "domain-config" })

        val domainConfigs = root.children("domain-config")
        // Every domain-config either inherits the base anchors or trusts exactly the system CAs, for FIS only.
        val firebase = listOf("firebaseinstallations.googleapis.com" to "false")
        domainConfigs.forEach {
            val anchors = it.anchors()
            assertTrue(anchors.isEmpty() || (anchors == listOf("system") && it.domains() == firebase))
            assertTrue(it.children("domain-config").isEmpty() && it.children("pin-set").isEmpty())
        }
        val trustingSystem = domainConfigs.filter { "system" in it.anchors() }
        assertEquals(1, trustingSystem.size)
        assertEquals(firebase, trustingSystem.single().domains())
        assertEquals("false", trustingSystem.single().getAttribute("cleartextTrafficPermitted"))

        val cleartext = domainConfigs.filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
            .flatMap { it.domains() }
        assertEquals(cleartextHosts, cleartext.map { it.first }.toSet())
        assertTrue(cleartext.all { it.second == "false" })
    }

    @Test
    fun releaseConfigTrustsOnlyIsrgExceptForFirebaseInstallationsAndAllowsNoCleartext() {
        assertShape("main", emptySet())
    }

    @Test
    fun debugConfigAddsOnlyLoopbackCleartext() {
        assertShape("debug", setOf("10.0.2.2", "localhost"))
    }
}
