package com.example.ptt.net

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private val server = MockWebServer()
    private val tokens = InMemoryDeviceTokenStore(token = "old-token", deviceId = "old-device")
    private val state = FakeEnrollmentState()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun get(): Int {
        val client = HttpClientFactory.create(AuthInterceptor(tokens, state), debug = true, log = {})
        return client.newCall(Request.Builder().url(server.url("/v1/contacts")).build()).execute().use { it.code }
    }

    @Test
    fun a401ForTheCurrentTokenMarksReenrollment() {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(401, get())
        assertTrue(state.needsReenroll)
    }

    @Test
    fun aLate401ForAnOldTokenDoesNotFlagAFreshEnrollment() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // A new enrollment completes while the old identity's request is in flight.
                tokens.save("new-device", "new-token")
                return MockResponse.Builder().code(401).build()
            }
        }
        assertEquals(401, get())
        assertFalse(state.needsReenroll)
    }

    @Test
    fun aSuccessNeverMarks() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        assertEquals(200, get())
        assertFalse(state.needsReenroll)
    }
}
