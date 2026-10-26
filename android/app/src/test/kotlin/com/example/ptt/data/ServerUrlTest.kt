package com.example.ptt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun acceptsBareHttps() {
        assertEquals("https://ptt.example.com", ServerUrl.normalize("https://ptt.example.com", debug = false))
        assertEquals("https://ptt.example.com", ServerUrl.normalize(" https://ptt.example.com/ ", debug = false))
        assertEquals("https://ptt.example.com:8443", ServerUrl.normalize("https://ptt.example.com:8443", debug = false))
    }

    @Test
    fun rejectsEverythingElse() {
        for (bad in listOf(
            "http://ptt.example.com",
            "ptt.example.com",
            "https://user:pw@ptt.example.com",
            "https://ptt.example.com/api",
            "https://ptt.example.com/?x=1",
            "https://ptt.example.com/#f",
            "ftp://ptt.example.com",
            ""
        )) {
            assertNull(bad, ServerUrl.normalize(bad, debug = false))
        }
    }

    @Test
    fun cleartextOnlyInDebugAndOnlyForLoopbackHosts() {
        assertEquals("http://10.0.2.2:8080", ServerUrl.normalize("http://10.0.2.2:8080", debug = true))
        assertEquals("http://localhost:8080", ServerUrl.normalize("http://localhost:8080", debug = true))
        assertNull(ServerUrl.normalize("http://10.0.2.2:8080", debug = false))
        assertNull(ServerUrl.normalize("http://ptt.example.com", debug = true))
    }
}
