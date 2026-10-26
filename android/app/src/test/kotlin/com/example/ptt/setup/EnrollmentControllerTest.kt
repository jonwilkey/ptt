package com.example.ptt.setup

import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.DeviceIdentity
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.DeviceKeysUnavailable
import com.example.ptt.crypto.EnrollmentMac
import com.example.ptt.crypto.EnrollmentQr
import com.example.ptt.crypto.RosterDecision
import com.example.ptt.crypto.RosterVerifier
import com.example.ptt.crypto.SignedObject
import com.example.ptt.crypto.TrustedAdmin
import com.example.ptt.data.ContactEntity
import com.example.ptt.data.OutboxEntity
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnrollmentControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var f: SetupFixture

    @Before
    fun setUp() {
        f = SetupFixture(tmp.newFolder())
    }

    @After
    fun tearDown() = f.close()

    private fun keysGone(): Boolean = try {
        f.keyStore.load()
        false
    } catch (_: DeviceKeysUnavailable) {
        true
    }

    // ---- bootstrap phone ----

    @Test
    fun bootstrapEnrollSendsNoMacAndPublishesRosterV1() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        f.serveContacts()

        val result = f.controller.enroll(f.bootstrapQr(), "  Sam phone ")
        assertEquals(EnrollResult.Success, result)

        val enroll = f.enrollRequest()
        assertFalse("bootstrap sends no mac", "mac" in enroll)
        assertFalse("no fcm token when Firebase is unavailable", "fcm_token" in enroll)
        assertEquals("0.2.0", enroll.getValue("app_version").jsonPrimitive.content)
        assertEquals("Sam phone", enroll.getValue("device_name").jsonPrimitive.content)
        assertEquals(1, f.outboxResumes)

        // The PUT body is a roster signed under ptt-roster-v1 by this device: version 1, only itself, admin.
        val putBody = f.fake.bodies("PUT", "/v1/roster").single()
        val signed = SignedObject.fromWire(putBody)
        val keys = DeviceKeys.load(f.keyStore)
        val self = DeviceIdentity(f.selfId(), keys.ed25519Public, keys.x25519Public)
        val decision = RosterVerifier.accept(
            null,
            listOf(TrustedAdmin(self.deviceId, keys.ed25519Public)),
            self,
            signed
        )
        val roster = (decision as RosterDecision.Accepted).roster
        assertEquals(1L, roster.version)
        assertEquals(listOf(self.deviceId), roster.devices.map { it.id })
        assertTrue(roster.devices.single().admin)

        // Stored byte for byte; the trust anchor is gone; the app is ready.
        assertEquals(putBody, f.rosters.current()!!.signedJson)
        assertEquals(1L, f.rosters.current()!!.version)
        assertTrue(f.settings.trustAnchor().isEmpty())
        assertEquals(Route.Home, f.startup.resolve())
        assertEquals("secret-token", f.tokens.token())
        assertEquals(1, f.contacts.contacts.first().size)
    }

    @Test
    fun bootstrapRetriesThePutOnNetworkFailure() = runBlocking {
        f.serveEnroll(admin = true)
        var puts = 0
        f.fake.on("PUT", "/v1/roster") { FakeServer.response(if (++puts < 3) 503 else 204) }
        assertEquals(EnrollResult.Success, f.controller.enroll(f.bootstrapQr(), "Sam phone"))
        assertEquals(3, puts)
        assertEquals(Route.Home, f.startup.resolve())
    }

    @Test
    fun bootstrapThatNeverPublishesIsRetriedByTheNextSync() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 503)
        assertEquals(EnrollResult.Success, f.controller.enroll(f.bootstrapQr(), "Sam phone"))
        assertEquals(Route.Waiting, f.startup.resolve())

        f.fake.reply("PUT", "/v1/roster", 204)
        assertEquals(SyncResult.Updated, f.sync.sync())
        assertEquals(Route.Home, f.startup.resolve())
    }

    @Test
    fun bootstrapRosterAlreadyOnServerFallsBackToNormalSync() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 409, """{"error":"roster_stale"}""")
        // Someone else's roster: this phone only trusts itself, so it is refused but nothing breaks.
        val other = TestAdmin()
        f.serveRoster(f.signedRoster(other.keys, other.id, listOf(other.device), 5))
        assertEquals(EnrollResult.Success, f.controller.enroll(f.bootstrapQr(), "Sam phone"))
        assertTrue(f.fake.count("GET", "/v1/roster") >= 1)
        assertNull(f.rosters.current())
        assertEquals(Route.Waiting, f.startup.resolve())
    }

    @Test
    fun bootstrapPhoneThatIsNotAdminFailsAndWipes() = runBlocking {
        f.serveEnroll(admin = false)
        val result = f.controller.enroll(f.bootstrapQr(), "Sam phone")
        assertEquals(EnrollResult.Failure(EnrollError.NOT_ADMIN), result)
        assertTrue(keysGone())
        assertNull(f.tokens.token())
        assertFalse(f.settings.enrolled.first())
        assertEquals(0, f.fake.count("PUT", "/v1/roster"))
    }

    // ---- admin QR ----

    @Test
    fun adminEnrollSendsTheProtocolMac() = runBlocking {
        val admin = TestAdmin()
        val secret = ByteArray(32) { 3 }
        f.serveEnroll(admin = false)
        assertEquals(EnrollResult.Success, f.controller.enroll(f.adminQr(admin, secret), "Sam phone"))

        val req = f.enrollRequest()
        val code = ByteArray(16) { 7 }
        val expected = EnrollmentMac.compute(
            secret,
            code,
            req.getValue("device_id").jsonPrimitive.content,
            B64u.decode(req.getValue("ed25519").jsonPrimitive.content),
            B64u.decode(req.getValue("x25519").jsonPrimitive.content)
        )
        assertEquals(B64u.encode(expected), req.getValue("mac").jsonPrimitive.content)
        assertEquals(B64u.encode(code), req.getValue("code").jsonPrimitive.content)
        // Trust anchor holds the QR's admin ids and keys only.
        val anchor = f.settings.trustAnchor()
        assertEquals(listOf(admin.id), anchor.map { it.id })
        assertTrue(anchor.single().ed25519.contentEquals(admin.keys.ed25519Public))
        assertEquals(Route.Waiting, f.startup.resolve())
    }

    @Test
    fun adminQrRosterIsAcceptedOnlyFromAQrAdminListingSelf() = runBlocking {
        val admin = TestAdmin()
        val stranger = TestAdmin("11111111-2222-4333-8444-555555555555")
        f.serveEnroll(admin = false)
        f.serveContacts()
        f.controller.enroll(f.adminQr(admin), "Sam phone")
        val me = f.selfEntry()

        // Untrusted signer, self not listed, tampered signature: all rejected, nothing stored.
        f.serveRoster(f.signedRoster(stranger.keys, stranger.id, listOf(stranger.device, me), 2))
        assertEquals(SyncResult.Rejected("UNTRUSTED_SIGNER"), f.sync.sync())
        f.serveRoster(f.signedRoster(admin.keys, admin.id, listOf(admin.device), 2))
        assertEquals(SyncResult.Rejected("SELF_NOT_LISTED"), f.sync.sync())
        f.serveRoster(f.signedRoster(admin.keys, admin.id, listOf(admin.device, me), 2, tamper = true))
        assertEquals(SyncResult.Rejected("BAD_SIGNATURE"), f.sync.sync())
        assertNull(f.rosters.current())
        assertEquals(Route.Waiting, f.startup.resolve())
        assertTrue(f.logs.none { it.contains("secret") || it.contains("Sam") })

        // The right roster is accepted and stored exactly as received; the anchor is dropped.
        val good = f.signedRoster(admin.keys, admin.id, listOf(admin.device, me), 2)
        f.serveRoster(good)
        assertEquals(SyncResult.Updated, f.sync.sync())
        assertEquals(good, f.rosters.current()!!.signedJson)
        assertTrue(f.settings.trustAnchor().isEmpty())
        assertEquals(Route.Home, f.startup.resolve())
        assertEquals(1, f.contacts.contacts.first().size)

        // Equal and older versions are a no-op.
        assertEquals(SyncResult.NoChange, f.sync.sync())
        val logsBefore = f.logs.size
        assertEquals(SyncResult.NoChange, f.sync.sync())
        assertEquals("an equal version is silent", logsBefore, f.logs.size)
        f.serveRoster(f.signedRoster(admin.keys, admin.id, listOf(admin.device, me), 1))
        assertEquals(SyncResult.NoChange, f.sync.sync())
        assertEquals(
            "a rollback is logged by reason",
            listOf("roster rejected: STALE_VERSION"),
            f.logs.drop(logsBefore)
        )
        assertEquals(good, f.rosters.current()!!.signedJson)

        // A newer roster is verified against the CURRENT roster, not the QR.
        val v3 = f.signedRoster(admin.keys, admin.id, listOf(admin.device, me), 3)
        f.serveRoster(v3)
        assertEquals(SyncResult.Updated, f.sync.sync())
        assertEquals(3L, f.rosters.current()!!.version)
    }

    @Test
    fun noRosterYetIsANoOp() = runBlocking {
        f.serveEnroll(admin = false)
        f.controller.enroll(f.adminQr(TestAdmin()), "Sam phone")
        assertEquals(SyncResult.NoRoster, f.sync.sync())
    }

    @Test
    fun rosterWithoutThisDeviceSendsItBackToEnrollment() = runBlocking {
        val admin = TestAdmin()
        f.serveEnroll(admin = false)
        f.controller.enroll(f.adminQr(admin), "Sam phone")
        val me = f.selfEntry()
        f.serveRoster(f.signedRoster(admin.keys, admin.id, listOf(admin.device, me), 2))
        f.sync.sync()
        f.serveRoster(f.signedRoster(admin.keys, admin.id, listOf(admin.device), 3))
        assertEquals(SyncResult.Removed, f.sync.sync())
        assertTrue(f.settings.needsReenrollment())

        assertEquals(Route.Enroll(explain = true), f.startup.resolve())
        assertTrue(keysGone())
        assertNull(f.tokens.token())
        assertNull(f.rosters.current())
        // The explanation survives until the next enrollment.
        assertEquals(Route.Enroll(explain = true), f.startup.resolve())
    }

    // ---- failures and wiping ----

    @Test
    fun serverErrorsMapToMessagesAndLeaveNothingBehind() = runBlocking {
        val cases = listOf(
            Triple(400, """{"error":"code_invalid"}""", EnrollError.CODE_INVALID),
            Triple(429, """{"error":"rate_limited"}""", EnrollError.RATE_LIMITED),
            Triple(503, "", EnrollError.NETWORK),
            Triple(400, """{"error":"invalid_request"}""", EnrollError.OTHER)
        )
        for ((status, body, error) in cases) {
            f.fake.reply("POST", "/v1/enroll", status, body)
            assertEquals(EnrollResult.Failure(error), f.controller.enroll(f.bootstrapQr(), "Sam phone"))
            assertTrue("keys wiped after $status", keysGone())
            assertNull(f.tokens.token())
            assertFalse(f.settings.enrolled.first())
        }
    }

    @Test
    fun priorLocalStateIsWipedBeforeEnrolling() = runBlocking {
        f.tokens.save("old-device", "old-token")
        f.keyStore.create()
        f.settings.setEnrolled("https://old.example.com")
        f.rosters.save(9, "{}", "{}")
        f.contacts.replaceAll(listOf(com.example.ptt.net.ContactDto(USER, "Old", false, "ok")))
        f.activePage.start("p", PageRole.SENDER, "s", USER, "normal", "ringing")
        f.db.outboxDao().insert(OutboxEntity("o", OutboxKind.CANCEL, "p", "", 0, 0, 0))
        f.fake.reply("POST", "/v1/enroll", 503)

        f.controller.enroll(f.bootstrapQr(), "Sam phone")

        assertNull(f.rosters.current())
        assertTrue(f.contacts.contacts.first().isEmpty())
        assertNull(f.activePage.current())
        assertTrue(f.db.outboxDao().all().isEmpty())
        assertNull(f.tokens.deviceId())
        assertNull(f.settings.serverUrl())
        assertTrue(keysGone())
    }

    @Test
    fun invalidInputDoesNotWipeAWorkingSetup() = runBlocking {
        f.tokens.save("dev", "tok")
        f.settings.setEnrolled("https://ptt.example.com")
        assertEquals(EnrollResult.Failure(EnrollError.INVALID_QR), f.controller.enroll("not a qr", "Sam phone"))
        assertEquals(EnrollResult.Failure(EnrollError.INVALID_NAME), f.controller.enroll(f.bootstrapQr(), "   "))
        val httpQr = EnrollmentQr("http://example.com", ByteArray(16), null, emptyList()).encode()
        assertEquals(EnrollResult.Failure(EnrollError.INVALID_QR), f.controller.enroll(httpQr, "Sam phone"))
        assertEquals("tok", f.tokens.token())
        assertTrue(f.settings.enrolled.first())
        assertEquals(0, f.fake.requests.size)
    }

    // ---- failures after the enrollment is stored keep the device enrolled ----

    private suspend fun assertStillEnrolledAndWaiting() {
        assertFalse("keys kept", keysGone())
        assertEquals("secret-token", f.tokens.token())
        assertTrue(f.settings.enrolled.first())
        assertEquals(Route.Waiting, f.startup.resolve())
    }

    @Test
    fun anExceptionAfterTheEnrollmentIsStoredKeepsTheDeviceEnrolled() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        f.resumeFailure = IllegalStateException("boom")
        assertEquals(EnrollResult.Success, f.controller.enroll(f.bootstrapQr(), "Sam phone"))
        assertStillEnrolledAndWaiting()
    }

    @Test
    fun aRefusedBootstrapRosterKeepsTheDeviceEnrolled() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 400, """{"error":"roster_invalid"}""")
        assertEquals(EnrollResult.Success, f.controller.enroll(f.bootstrapQr(), "Sam phone"))
        assertStillEnrolledAndWaiting()
    }

    @Test
    fun aBootstrapRosterThatCannotBeBuiltIsRejectedNotThrown() = runBlocking {
        // An empty display name from the server makes roster v1 ill-formed (ProtocolException while building).
        f.serveEnroll(admin = true, displayName = "")
        f.fake.reply("PUT", "/v1/roster", 204)
        assertEquals(EnrollResult.Success, f.controller.enroll(f.bootstrapQr(), "Sam phone"))
        assertEquals(0, f.fake.count("PUT", "/v1/roster"))
        assertEquals(SyncResult.Rejected("bootstrap_invalid"), f.sync.publishBootstrap())
        assertStillEnrolledAndWaiting()
    }

    // ---- only the bootstrap phone publishes ----

    @Test
    fun anAdminPhoneEnrolledByAnAdminQrNeverPublishesARoster() = runBlocking {
        val admin = TestAdmin()
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        assertEquals(EnrollResult.Success, f.controller.enroll(f.adminQr(admin), "Sam phone"))
        assertTrue(f.settings.profile()!!.admin)
        assertEquals(SyncResult.NoRoster, f.sync.sync())
        assertEquals(SyncResult.NoChange, f.sync.publishBootstrap())
        assertEquals(0, f.fake.count("PUT", "/v1/roster"))
        assertEquals(Route.Waiting, f.startup.resolve())
    }

    @Test
    fun publishBootstrapIsANoOpOnceARosterIsHeld() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        f.controller.enroll(f.bootstrapQr(), "Sam phone")
        assertEquals(1, f.fake.count("PUT", "/v1/roster"))
        assertEquals(SyncResult.NoChange, f.sync.publishBootstrap())
        assertEquals(1, f.fake.count("PUT", "/v1/roster"))
    }

    // ---- enrollment and start-up routing are serialised ----

    @Test
    fun routingWhileAnEnrollmentIsInFlightDoesNotWipeItsKeys() = runBlocking {
        // A removed phone: flag set. The scanner returns and ON_START's resolve() races the enroll() call.
        f.settings.markNeedsReenrollment()
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        f.fake.on("POST", "/v1/enroll") { request ->
            arrived.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val id = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!.utf8())
                .let { (it as kotlinx.serialization.json.JsonObject).getValue("device_id") }
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            FakeServer.response(
                201,
                """{"token":"secret-token","device_id":"$id","user_id":"$USER","display_name":"Sam","admin":false}"""
            )
        }
        val adminQr = f.adminQr(TestAdmin())
        val enrolling = async(kotlinx.coroutines.Dispatchers.IO) { f.controller.enroll(adminQr, "Sam phone") }
        assertTrue(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val routing = async(kotlinx.coroutines.Dispatchers.IO) { f.startup.resolve() }
        kotlinx.coroutines.delay(300) // let resolve() queue up behind the enrollment
        release.countDown()

        assertEquals(EnrollResult.Success, enrolling.await())
        assertEquals("resolve sees the new enrollment", Route.Waiting, routing.await())
        assertFalse("keys kept", keysGone())
        assertEquals("secret-token", f.tokens.token())
        assertTrue(f.settings.enrolled.first())
        assertFalse(f.settings.needsReenrollment())
    }

    // ---- Keystore failures ----

    @Test
    fun keyCreationFailureIsReportedAndWiped() = runBlocking {
        f.keyStore.failCreate = DeviceKeysUnavailable("no keystore")
        assertEquals(
            EnrollResult.Failure(EnrollError.KEYS_UNAVAILABLE),
            f.controller.enroll(f.bootstrapQr(), "Sam phone")
        )
        assertEquals(0, f.fake.count("POST", "/v1/enroll"))
        assertFalse(f.settings.enrolled.first())
    }

    @Test
    fun tokenStorageFailureWipesTheKeys() = runBlocking {
        val broken = SetupFixture(tmp.newFolder(), tokenStoreFails = true)
        try {
            broken.serveEnroll(admin = true)
            val result = broken.controller.enroll(broken.bootstrapQr(), "Sam phone")
            assertEquals(EnrollResult.Failure(EnrollError.KEYS_UNAVAILABLE), result)
            assertFalse(broken.settings.enrolled.first())
            assertTrue(
                try {
                    broken.keyStore.load()
                    false
                } catch (_: DeviceKeysUnavailable) {
                    true
                }
            )
        } finally {
            broken.close()
        }
    }

    @Test
    fun aTransientKeystoreErrorGetsOneRetryBeforeTheDeviceIsGivenUp() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        f.controller.enroll(f.bootstrapQr(), "Sam phone")
        f.keyStore.failLoadTimes = 1
        assertEquals(Route.Home, f.startup.resolve())
        f.keyStore.failLoadTimes = 2
        assertEquals(Route.Enroll(explain = true), f.startup.resolve())
        assertNull(f.tokens.token())
    }

    // ---- start-up routing ----

    @Test
    fun lostKeysOrA401SendTheDeviceBackToEnrollment() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        f.controller.enroll(f.bootstrapQr(), "Sam phone")
        assertEquals(Route.Home, f.startup.resolve())

        f.keyStore.wipe()
        assertEquals(Route.Enroll(explain = true), f.startup.resolve())
        assertNull(f.tokens.token())
        assertNull(f.rosters.current())

        // 401 from any call marks the flag; the next routing wipes.
        f.serveEnroll(admin = true)
        f.controller.enroll(f.bootstrapQr(), "Sam phone")
        assertEquals(Route.Home, f.startup.resolve())
        f.fake.reply("GET", "/v1/roster", 401, """{"error":"unauthorized"}""")
        assertEquals(SyncResult.NotEnrolled, f.sync.sync())
        assertEquals(Route.Enroll(explain = true), f.startup.resolve())
        assertTrue(keysGone())
    }

    // ---- device registration ----

    @Test
    fun registrationOnlySendsWhatChanged() = runBlocking {
        f.fcmToken = "fcm-one"
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        f.fake.reply("PUT", "/v1/devices/me", 204)
        f.controller.enroll(f.bootstrapQr(), "Sam phone")
        // The enrollment request already carried all three values.
        assertEquals("fcm-one", f.enrollRequest().getValue("fcm_token").jsonPrimitive.content)
        assertTrue(f.registration.sync())
        assertEquals(0, f.fake.count("PUT", "/v1/devices/me"))

        f.fcmToken = "fcm-two"
        assertTrue(f.registration.sync())
        assertEquals("""{"fcm_token":"fcm-two"}""", f.fake.bodies("PUT", "/v1/devices/me").last())

        f.appVersion = "0.3.0"
        assertTrue(f.registration.sync())
        assertEquals("""{"app_version":"0.3.0"}""", f.fake.bodies("PUT", "/v1/devices/me").last())

        val profile = f.settings.profile()!!
        f.settings.saveProfile(profile.copy(deviceName = "Kitchen phone"))
        assertTrue(f.registration.sync())
        assertEquals("""{"device_name":"Kitchen phone"}""", f.fake.bodies("PUT", "/v1/devices/me").last())

        assertTrue(f.registration.sync())
        assertEquals(3, f.fake.count("PUT", "/v1/devices/me"))

        // onNewToken path; and a failed PUT is retried next time.
        f.fake.reply("PUT", "/v1/devices/me", 503)
        assertFalse(f.registration.onNewToken("fcm-three"))
        f.fake.reply("PUT", "/v1/devices/me", 204)
        assertTrue(f.registration.onNewToken("fcm-three"))
        assertEquals("""{"fcm_token":"fcm-three"}""", f.fake.bodies("PUT", "/v1/devices/me").last())
    }

    @Test
    fun aTokenThatAppearsLaterIsRegistered() = runBlocking {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        f.fake.reply("PUT", "/v1/devices/me", 204)
        f.controller.enroll(f.bootstrapQr(), "Sam phone")
        f.fcmToken = "late"
        assertTrue(f.registration.sync())
        assertEquals("""{"fcm_token":"late"}""", f.fake.bodies("PUT", "/v1/devices/me").single())
    }

    @Test
    fun enrollmentStoresTheProfileAndOnlyTheAdminKeysAsAnchor() = runBlocking {
        val admin = TestAdmin()
        f.serveEnroll(admin = false)
        f.controller.enroll(f.adminQr(admin, ByteArray(32) { 3 }), "Sam phone")
        val anchor = f.settings.trustAnchor()
        assertEquals(1, anchor.size)
        assertEquals(setOf(admin.id), anchor.map { it.id }.toSet())
        assertEquals(USER, f.settings.profile()!!.userId)
        assertFalse(f.settings.profile()!!.admin)
        assertEquals("Sam phone", f.settings.profile()!!.deviceName)
    }
}
