package com.example.ptt.net

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseHasNoImpairmentTest {
    @Test
    fun theTransportSeamReturnsTheTransportItWasGiven() {
        val transport = object : TalkTransport {
            override suspend fun open(listener: TransportListener): TransportSocket? = null
        }
        assertSame(transport, impairTransport(transport))
    }

    @Test
    fun noImpairmentClassIsInTheReleaseBuild() {
        for (name in listOf("ImpairedTransport", "DebugImpairment", "ImpairmentReceiver")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName("com.example.ptt.net.$name") }
        }
    }
}
