package com.example.ptt.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientFactoryTest {
    private val auth = AuthInterceptor(InMemoryDeviceTokenStore("t", "d"), FakeEnrollmentState("https://example.com"))

    @Test
    fun releaseAllowsOnlyTls13AndNoCleartext() {
        val client = HttpClientFactory.create(auth, debug = false, log = {})
        assertEquals(1, client.connectionSpecs.size)
        val spec = client.connectionSpecs.single()
        assertTrue(spec.isTls)
        assertEquals(listOf(TlsVersion.TLS_1_3), spec.tlsVersions)
        assertFalse(client.connectionSpecs.contains(ConnectionSpec.CLEARTEXT))
    }

    @Test
    fun debugAddsCleartextButKeepsTls13Only() {
        val client = HttpClientFactory.create(auth, debug = true, log = {})
        assertEquals(listOf(TlsVersion.TLS_1_3), client.connectionSpecs.first().tlsVersions)
        assertTrue(client.connectionSpecs.contains(ConnectionSpec.CLEARTEXT))
    }

    @Test
    fun releaseClientRefusesCleartext() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(204).build())
            val client = HttpClientFactory.create(auth, debug = false, log = {})
            val call = client.newCall(Request.Builder().url(server.url("/v1/contacts")).build())
            val failure = runCatching { call.execute() }
            assertTrue(failure.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun clientHasTimeoutsAndDoesNotFollowRedirects() {
        val client = HttpClientFactory.create(auth, debug = false, log = {})
        assertTrue(client.connectTimeoutMillis in 1..30_000)
        assertTrue(client.readTimeoutMillis in 1..60_000)
        assertTrue(client.callTimeoutMillis in 1..120_000)
        assertFalse(client.followRedirects)
    }

    @Test
    fun debugLogNeverContainsTokensUrlsOrBodies() {
        val lines = mutableListOf<String>()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("""{"secret":"BODYSECRET"}""").build())
            val client = HttpClientFactory.create(auth, debug = true, log = { lines += it })
            val url = server.url("/v1/pages/PAGEID/delivered")
            val request = Request.Builder().url(url).post(okhttp3.RequestBody.EMPTY).build()
            client.newCall(request).execute().close()
        }
        assertEquals(listOf("POST 200"), lines)
    }
}
