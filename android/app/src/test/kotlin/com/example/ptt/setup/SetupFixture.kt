package com.example.ptt.setup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.Contexts
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.EnrollmentQr
import com.example.ptt.crypto.InMemoryDeviceKeyStore
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDevice
import com.example.ptt.crypto.SignedObject
import com.example.ptt.crypto.TrustedAdmin
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.ContactRepository
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.data.TestDb
import com.example.ptt.net.AuthInterceptor
import com.example.ptt.net.DeviceTokenUnavailable
import com.example.ptt.net.HttpClientFactory
import com.example.ptt.net.InMemoryDeviceTokenStore
import com.example.ptt.net.OkHttpPttApi
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

const val USER = "0b7e4c1d-9a2f-4e68-b3c5-71d0a9e2f845"
const val ADMIN_USER = "5a1c0f3e-2b7d-4c9a-8e11-3f6d9b0a7c42"
const val ISSUED_AT = 1_789_920_000_000L

/** Path-routed MockWebServer dispatcher that records every request. */
class FakeServer : Dispatcher() {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val handlers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    fun on(method: String, path: String, handler: (RecordedRequest) -> MockResponse) {
        handlers["$method $path"] = handler
    }

    fun reply(method: String, path: String, code: Int, body: String = "") = on(method, path) { response(code, body) }

    fun bodies(method: String, path: String): List<String> =
        requests.filter { it.method == method && it.url.encodedPath == path }.map { it.body?.utf8().orEmpty() }

    fun count(method: String, path: String) = bodies(method, path).size

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        return handlers["${request.method} ${request.url.encodedPath}"]?.invoke(request)
            ?: response(404, """{"error":"not_found"}""")
    }

    companion object {
        fun response(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()
    }
}

/** An admin device with real in-memory Tink keys, to sign rosters and appear in QRs. */
class TestAdmin(val id: String = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57") {
    val keys: DeviceKeys = DeviceKeys.create(InMemoryDeviceKeyStore())
    val device = RosterDevice(id, ADMIN_USER, "Pat", "Pat phone", true, keys.ed25519Public, keys.x25519Public)
    val trusted get() = TrustedAdmin(id, keys.ed25519Public)
}

/** The code under test with real Room, DataStore, OkHttp and a MockWebServer; only keys and tokens are in memory. */
class SetupFixture(tmp: File, tokenStoreFails: Boolean = false) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val web = MockWebServer()
    val fake = FakeServer()
    val db = TestDb.inMemory(context)
    val clock: Clock = Clock.fixed(Instant.ofEpochMilli(ISSUED_AT), ZoneOffset.UTC)
    val settings = SettingsRepository(PreferenceDataStoreFactory.create { File(tmp, "settings.preferences_pb") })
    val tokens = InMemoryDeviceTokenStore(failWith = if (tokenStoreFails) DeviceTokenUnavailable("test") else null)
    val keyStore = InMemoryDeviceKeyStore()
    val rosters = RosterRepository(db.rosterDao(), clock)
    val contacts = ContactRepository(db.contactDao(), clock)
    val activePage = ActivePageRepository(db.activePageDao(), clock)
    var fcmToken: String? = null
    var appVersion = "0.2.0"
    var outboxResumes = 0

    /** Thrown by the controller's "resume outbox" step, to simulate a failure after enrollment is stored. */
    var resumeFailure: RuntimeException? = null
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
    val api = OkHttpPttApi(
        HttpClientFactory.create(AuthInterceptor(tokens, settings), debug = true, log = {}),
        settings,
        json,
        Dispatchers.IO
    )
    val wiper = LocalStateWiper(settings, tokens, rosters, contacts, activePage, db.outboxDao(), keyStore)
    val fcm = FcmTokenSource { fcmToken }
    val logs = mutableListOf<String>()
    val sync = RosterSync(api, rosters, contacts, settings, tokens, keyStore, clock) { logs += it }
    val startup = AppStartup(settings, tokens, keyStore, rosters, wiper)
    val registration get() = DeviceRegistration(api, settings, fcm, appVersion)
    val controller get() = EnrollmentController(
        api, keyStore, tokens, settings, wiper, sync, fcm,
        appVersion = appVersion,
        debug = true,
        resumeOutbox = {
            resumeFailure?.let { throw it }
            outboxResumes++
        },
        bootstrapRetryDelayMs = 0
    )

    val url get() = "http://localhost:${web.port}"

    init {
        web.dispatcher = fake
        web.start()
    }

    fun close() {
        web.close()
        db.close()
    }

    fun bootstrapQr(): String = EnrollmentQr(url, ByteArray(16) { 7 }, null, emptyList()).encode()

    fun adminQr(admin: TestAdmin, secret: ByteArray = ByteArray(32) { 9 }): String =
        EnrollmentQr(url, ByteArray(16) { 7 }, secret, listOf(admin.trusted)).encode()

    fun enrollRequest(): JsonObject = Json.parseToJsonElement(fake.bodies("POST", "/v1/enroll").last()).jsonObject

    fun selfId(): String = enrollRequest().getValue("device_id").jsonPrimitive.content

    /** The server answers `POST /v1/enroll` with a token for whatever device id was sent. */
    fun serveEnroll(admin: Boolean, displayName: String = "Sam", user: String = USER) {
        fake.on("POST", "/v1/enroll") { request ->
            val id = Json.parseToJsonElement(
                request.body!!.utf8()
            ).jsonObject.getValue("device_id").jsonPrimitive.content
            FakeServer.response(
                201,
                """{"token":"secret-token","device_id":"$id","user_id":"$user",""" +
                    """"display_name":"$displayName","admin":$admin}"""
            )
        }
    }

    /** This device's roster entry, built from what it sent at enrollment. */
    fun selfEntry(admin: Boolean = false, deviceName: String = "Sam phone"): RosterDevice {
        val req = enrollRequest()
        return RosterDevice(
            selfId(),
            USER,
            "Sam",
            deviceName,
            admin,
            B64u.decode(req.getValue("ed25519").jsonPrimitive.content),
            B64u.decode(req.getValue("x25519").jsonPrimitive.content)
        )
    }

    /** Wire JSON of a roster signed by [signer] (as [signerId]); [tamper] flips one bit of the signature. */
    fun signedRoster(
        signer: DeviceKeys,
        signerId: String,
        devices: List<RosterDevice>,
        version: Long,
        tamper: Boolean = false
    ): String {
        val roster = Roster(version, ISSUED_AT, devices.sortedBy { it.id })
        val signed = signer.signObject(Contexts.ROSTER, signerId, roster.encodePayload())
        if (!tamper) return signed.toWire()
        val sig = signed.sig.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        return SignedObject(signed.payload, signerId, sig).toWire()
    }

    fun serveRoster(wire: String) = fake.reply("GET", "/v1/roster", 200, wire)

    fun serveContacts() = fake.reply(
        "GET",
        "/v1/contacts",
        200,
        """{"users":[{"id":"$USER","display_name":"Sam","admin":false,"presence":"ok"}]}"""
    )
}
