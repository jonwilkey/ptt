package com.example.ptt.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PttApiTest {
    private val server = MockWebServer()
    private val tokens = InMemoryDeviceTokenStore(token = TOKEN, deviceId = DEVICE)
    private val state = FakeEnrollmentState()
    private lateinit var api: PttApi

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Before
    fun setUp() {
        server.start()
        state.url = server.url("/").toString().trimEnd('/')
        val client = HttpClientFactory.create(AuthInterceptor(tokens, state), debug = true, log = {})
        api = OkHttpPttApi(client, state, json, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun reply(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    private fun take(): RecordedRequest = server.takeRequest()

    private fun RecordedRequest.bodyJson(): JsonElement = Json.parseToJsonElement(body!!.utf8())

    // ---- request/response shapes, field names exactly as protocol section 6 ----

    @Test
    fun enrollSendsProtocolFieldsWithoutAuth() = runBlocking {
        reply(201, """{"token":"$TOKEN","device_id":"$DEVICE","user_id":"$USER","display_name":"Alex","admin":false}""")
        val request = EnrollRequest(
            code = "c".repeat(22),
            deviceId = DEVICE,
            deviceName = "Alex's phone",
            ed25519 = "e".repeat(43),
            x25519 = "x".repeat(43),
            mac = "m".repeat(43),
            fcmToken = "fcm",
            appVersion = "0.2.0"
        )
        // enroll works before anything is stored: it uses the URL passed in.
        state.url = null
        val result = api.enroll(server.url("/").toString().trimEnd('/'), request)

        val http = take()
        assertEquals("POST", http.method)
        assertEquals("/v1/enroll", http.url.encodedPath)
        assertNull(http.headers["Authorization"])
        assertEquals(
            buildJsonObject {
                put("code", "c".repeat(22))
                put("device_id", DEVICE)
                put("device_name", "Alex's phone")
                put("ed25519", "e".repeat(43))
                put("x25519", "x".repeat(43))
                put("mac", "m".repeat(43))
                put("fcm_token", "fcm")
                put("app_version", "0.2.0")
            },
            http.bodyJson()
        )
        val ok = result as ApiResult.Success
        assertEquals(EnrollResponse(TOKEN, DEVICE, USER, "Alex", false), ok.body)
    }

    @Test
    fun bootstrapEnrollOmitsMac() = runBlocking {
        reply(201, """{"token":"t","device_id":"$DEVICE","user_id":"$USER","display_name":"A","admin":true}""")
        api.enroll(
            state.url!!,
            EnrollRequest("c", DEVICE, "n", "e", "x", mac = null, fcmToken = "f", appVersion = "1")
        )
        val obj = (take().bodyJson() as kotlinx.serialization.json.JsonObject)
        assertFalse("mac" in obj)
        assertEquals(7, obj.size)
    }

    @Test
    fun createEnrollmentCode() = runBlocking {
        reply(201, """{"code":"cc","code_id":"ii","expires_at":1789920000000}""")
        val result = api.createEnrollmentCode(
            CreateEnrollmentCodeRequest(displayName = "Alex", makeAdmin = false, userId = USER)
        )
        val http = take()
        assertEquals("POST /v1/enrollment-codes", "${http.method} ${http.url.encodedPath}")
        assertEquals(
            buildJsonObject {
                put("display_name", "Alex")
                put("make_admin", false)
                put("user_id", USER)
            },
            http.bodyJson()
        )
        assertEquals(CreateEnrollmentCodeResponse("cc", "ii", 1789920000000), (result as ApiResult.Success).body)
    }

    @Test
    fun createEnrollmentCodeOmitsAbsentFields() = runBlocking {
        reply(201, """{"code":"cc","code_id":"ii","expires_at":1}""")
        api.createEnrollmentCode(CreateEnrollmentCodeRequest(displayName = "Alex"))
        assertEquals(buildJsonObject { put("display_name", "Alex") }, take().bodyJson())
    }

    @Test
    fun getEnrollmentCodeUsed() = runBlocking {
        reply(
            200,
            """{"status":"used","device":{"id":"$DEVICE","user_id":"$USER","display_name":"Alex",""" +
                """"device_name":"Alex's phone","admin":false,"ed25519":"e","x25519":"x","mac":"m"}}"""
        )
        val result = api.getEnrollmentCode("codeid")
        val http = take()
        assertEquals("GET /v1/enrollment-codes/codeid", "${http.method} ${http.url.encodedPath}")
        val body = (result as ApiResult.Success).body
        assertEquals("used", body.status)
        assertEquals(EnrolledDeviceDto(DEVICE, USER, "Alex", "Alex's phone", false, "e", "x", "m"), body.device)
    }

    @Test
    fun enrolledDeviceWithoutMacDecodes() = runBlocking {
        // pttd omits "mac" for bootstrap codes.
        reply(
            200,
            """{"status":"used","device":{"id":"$DEVICE","user_id":"$USER","display_name":"A",""" +
                """"device_name":"n","admin":true,"ed25519":"e","x25519":"x"}}"""
        )
        val device = (api.getEnrollmentCode("c") as ApiResult.Success).body.device!!
        assertNull(device.mac)
    }

    @Test
    fun secretsDoNotAppearInToString() {
        val text = listOf(
            EnrollResponse("SECRETTOKEN", DEVICE, USER, "Alex", false),
            EnrollRequest("SECRETCODE", DEVICE, "n", "e", "x", "SECRETMAC", "SECRETFCM", "1"),
            UpdateDeviceRequest(fcmToken = "SECRETFCM"),
            CreateEnrollmentCodeResponse("SECRETCODE", "id", 1),
            EnrolledDeviceDto(DEVICE, USER, "A", "n", false, "e", "x", "SECRETMAC"),
            SignedObjectDto("SECRETPAYLOAD", DEVICE, "SECRETSIG"),
            CreatePageRequest(DEVICE, USER, "normal", SESSION, SignedObjectDto("SECRETPAYLOAD", DEVICE, "SECRETSIG")),
            RosterResponse("SECRETRAW", SignedObjectDto("SECRETPAYLOAD", DEVICE, "SECRETSIG"))
        ).joinToString("\n")
        assertFalse(text, "SECRET" in text)
    }

    @Test
    fun getEnrollmentCodePendingHasNoDevice() = runBlocking {
        reply(200, """{"status":"pending"}""")
        assertNull((api.getEnrollmentCode("c") as ApiResult.Success).body.device)
    }

    @Test
    fun updateDevice() = runBlocking {
        reply(204)
        val result = api.updateDevice(
            UpdateDeviceRequest("f", "0.2.0", "n", mapOf("battery_ok" to false))
        )
        val http = take()
        assertEquals("PUT /v1/devices/me", "${http.method} ${http.url.encodedPath}")
        assertEquals(
            buildJsonObject {
                put("fcm_token", "f")
                put("app_version", "0.2.0")
                put("device_name", "n")
                put("checklist", buildJsonObject { put("battery_ok", false) })
            },
            http.bodyJson()
        )
        assertEquals(ApiResult.Success(Unit), result)
    }

    @Test
    fun revokeDevice() = runBlocking {
        reply(204)
        assertEquals(ApiResult.Success(Unit), api.revokeDevice(DEVICE))
        val http = take()
        assertEquals("POST /v1/devices/$DEVICE/revoke", "${http.method} ${http.url.encodedPath}")
    }

    @Test
    fun getContacts() = runBlocking {
        reply(200, """{"users":[{"id":"$USER","display_name":"Alex","admin":true,"presence":"ok","future":1}]}""")
        val result = api.getContacts()
        assertEquals("GET /v1/contacts", take().let { "${it.method} ${it.url.encodedPath}" })
        assertEquals(
            listOf(ContactDto(USER, "Alex", true, "ok")),
            (result as ApiResult.Success).body.users
        )
    }

    @Test
    fun getRosterKeepsBodyExactlyAsReceived() = runBlocking {
        val raw = """{ "payload":"cGF5",  "signer":"$DEVICE", "sig":"c2ln" }"""
        reply(200, raw)
        val result = api.getRoster() as ApiResult.Success
        assertEquals(raw, result.body.rawJson)
        assertEquals(SignedObjectDto("cGF5", DEVICE, "c2ln"), result.body.signed)
    }

    @Test
    fun getRosterNotFoundIsFinal() = runBlocking {
        reply(404, """{"error":"not_found"}""")
        assertEquals(ApiResult.Final(404, "not_found"), api.getRoster())
    }

    @Test
    fun putRosterSendsTheSignedObjectVerbatim() = runBlocking {
        reply(204)
        val raw = """{"payload":"cGF5","signer":"$DEVICE","sig":"c2ln"}"""
        assertEquals(ApiResult.Success(Unit), api.putRoster(raw))
        val http = take()
        assertEquals("PUT /v1/roster", "${http.method} ${http.url.encodedPath}")
        assertEquals(raw, http.body!!.utf8())
        assertTrue(http.headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test
    fun createPage() = runBlocking {
        reply(201, """{"id":"$PAGE","state":"ringing"}""")
        val request = CreatePageRequest(PAGE, USER, "urgent", SESSION, SignedObjectDto("cGF5", DEVICE, "c2ln"))
        val result = api.createPage(request)
        val http = take()
        assertEquals("POST /v1/pages", "${http.method} ${http.url.encodedPath}")
        assertEquals(
            buildJsonObject {
                put("id", PAGE)
                put("to", USER)
                put("level", "urgent")
                put("session_id", SESSION)
                put(
                    "envelope",
                    buildJsonObject {
                        put("payload", "cGF5")
                        put("signer", DEVICE)
                        put("sig", "c2ln")
                    }
                )
            },
            http.bodyJson()
        )
        assertEquals(CreatePageResponse(PAGE, "ringing"), (result as ApiResult.Success).body)
    }

    @Test
    fun createPageIdempotentRetryIs200() = runBlocking {
        reply(200, """{"id":"$PAGE","state":"ended"}""")
        val request = CreatePageRequest(PAGE, USER, "normal", SESSION, SignedObjectDto("p", DEVICE, "s"))
        assertEquals(CreatePageResponse(PAGE, "ended"), (api.createPage(request) as ApiResult.Success).body)
    }

    @Test
    fun deliveredReturnsState() = runBlocking {
        reply(200, """{"state":"acknowledged"}""")
        val result = api.delivered(PAGE)
        val http = take()
        assertEquals("POST /v1/pages/$PAGE/delivered", "${http.method} ${http.url.encodedPath}")
        assertEquals(0, http.body!!.size)
        assertEquals(DeliveredResponse("acknowledged"), (result as ApiResult.Success).body)
    }

    @Test
    fun pageActionsPostToTheirOwnPaths() = runBlocking {
        repeat(3) { reply(204) }
        assertEquals(ApiResult.Success(Unit), api.acknowledge(PAGE))
        assertEquals(ApiResult.Success(Unit), api.answer(PAGE))
        assertEquals(ApiResult.Success(Unit), api.cancel(PAGE))
        assertEquals(
            listOf("acknowledge", "answer", "cancel").map { "POST /v1/pages/$PAGE/$it" },
            List(3) { take().let { r -> "${r.method} ${r.url.encodedPath}" } }
        )
    }

    // ---- status mapping (protocol section 6) ----

    @Test
    fun statusMappingTable() = runBlocking {
        data class Case(val status: Int, val body: String, val expected: ApiResult<Any?>)
        val cases = listOf(
            Case(400, """{"error":"invalid_request"}""", ApiResult.Final(400, "invalid_request")),
            Case(403, """{"error":"forbidden"}""", ApiResult.Final(403, "forbidden")),
            Case(404, """{"error":"not_found"}""", ApiResult.Final(404, "not_found")),
            Case(409, """{"error":"already_answered"}""", ApiResult.Final(409, "already_answered")),
            Case(409, """{"error":"conflict"}""", ApiResult.Final(409, "conflict")),
            Case(410, """{"error":"page_ended"}""", ApiResult.Final(410, "page_ended")),
            Case(401, """{"error":"unauthorized"}""", ApiResult.Final(401, "unauthorized")),
            Case(418, "not json", ApiResult.Final(418, null)),
            Case(500, """{"error":"internal"}""", ApiResult.Retryable(500)),
            Case(503, "", ApiResult.Retryable(503))
        )
        for (case in cases) {
            state.needsReenroll = false // a 401 case sets it
            reply(case.status, case.body)
            assertEquals("status ${case.status}", case.expected, api.answer(PAGE))
        }
    }

    @Test
    fun tooManyRequestsIsRetryableAfterRetryAfter() = runBlocking {
        reply(429, """{"error":"rate_limited"}""", "Retry-After" to "7")
        assertEquals(ApiResult.Retryable(429, retryAfterMs = 7_000), api.createPage(samplePage()))
    }

    @Test
    fun tooManyRequestsWithoutHeaderHasNoRetryAfter() = runBlocking {
        reply(429, """{"error":"rate_limited"}""")
        assertEquals(ApiResult.Retryable(429, retryAfterMs = null), api.createPage(samplePage()))
    }

    @Test
    fun retryAfterIsClampedAndGarbageIgnored() = runBlocking {
        reply(503, "", "Retry-After" to "999999")
        assertEquals(ApiResult.Retryable(503, retryAfterMs = 3_600_000), api.cancel(PAGE))
        reply(503, "", "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")
        assertEquals(ApiResult.Retryable(503, retryAfterMs = null), api.cancel(PAGE))
    }

    @Test
    fun malformedSuccessBodyIsFinalBadResponse() = runBlocking {
        reply(200, """{"unexpected":true}""")
        assertEquals(ApiResult.Final(200, ErrorCode.BAD_RESPONSE), api.delivered(PAGE))
    }

    @Test
    fun networkFailureIsRetryable() = runBlocking {
        server.close()
        val result = api.cancel(PAGE)
        assertTrue(result is ApiResult.Retryable && result.status == null && result.cause != null)
    }

    // ---- auth ----

    @Test
    fun authenticatedCallsCarryTheBearerToken() = runBlocking {
        reply(204)
        api.cancel(PAGE)
        assertEquals("Bearer $TOKEN", take().headers["Authorization"])
    }

    @Test
    fun enrollCarriesNoAuthorizationEvenWhenATokenIsStored() = runBlocking {
        reply(500)
        api.enroll(state.url!!, EnrollRequest("c", DEVICE, "n", "e", "x", null, "f", "1"))
        assertNull(take().headers["Authorization"])
    }

    @Test
    fun unauthorizedSetsTheReenrollFlagWithoutRetrying() = runBlocking {
        reply(401, """{"error":"unauthorized"}""")
        assertFalse(state.needsReenroll)
        val result = api.getContacts()
        assertEquals(ApiResult.Final(401, "unauthorized"), result)
        assertTrue((result as ApiResult.Final).isUnauthorized)
        assertTrue(state.needsReenroll)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anotherErrorDoesNotSetTheFlag() = runBlocking {
        reply(403, """{"error":"forbidden"}""")
        api.getContacts()
        assertFalse(state.needsReenroll)
    }

    @Test
    fun afterReenrollFlagCallsFailFastWithoutTouchingTheNetwork() = runBlocking {
        state.needsReenroll = true
        assertEquals(ApiResult.NotEnrolled(NotEnrolledReason.NEEDS_REENROLLMENT), api.getContacts())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun notEnrolledWithoutServerUrl() = runBlocking {
        state.url = null
        assertEquals(ApiResult.NotEnrolled(NotEnrolledReason.NOT_ENROLLED), api.getContacts())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun notEnrolledWithoutToken() = runBlocking {
        val noToken = InMemoryDeviceTokenStore()
        val client = HttpClientFactory.create(AuthInterceptor(noToken, state), debug = true, log = {})
        val bare = OkHttpPttApi(client, state, json, Dispatchers.IO)
        assertEquals(ApiResult.NotEnrolled(NotEnrolledReason.NOT_ENROLLED), bare.getContacts())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unusableTokenStoreFailsTypedAndNeverSendsPlaintext() = runBlocking {
        val broken = InMemoryDeviceTokenStore(failWith = DeviceTokenUnavailable("keystore"))
        val client = HttpClientFactory.create(AuthInterceptor(broken, state), debug = true, log = {})
        val bare = OkHttpPttApi(client, state, json, Dispatchers.IO)
        assertEquals(ApiResult.NotEnrolled(NotEnrolledReason.TOKEN_UNAVAILABLE), bare.cancel(PAGE))
        assertEquals(0, server.requestCount)
    }

    private fun samplePage() = CreatePageRequest(PAGE, USER, "normal", SESSION, SignedObjectDto("p", DEVICE, "s"))

    private companion object {
        const val TOKEN = "dG9rZW4"
        const val DEVICE = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57"
        const val USER = "0b7e4c1d-9a2f-4e68-b3c5-71d0a9e2f845"
        const val PAGE = "5f0c2a9e-3b1d-4c7a-9e21-6d8b0f4a7c13"
        const val SESSION = "a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60"
    }
}
