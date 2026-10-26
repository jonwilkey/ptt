package com.example.ptt.setup

import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.EnrollmentQr
import com.example.ptt.crypto.EnrollmentRejection
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDecision
import com.example.ptt.crypto.RosterDevice
import com.example.ptt.crypto.RosterVerifier
import com.example.ptt.crypto.SignedObject
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddDeviceFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: AdminHarness
    private val f get() = h.f
    private lateinit var settingsDir: File

    @Before
    fun setUp() = runBlocking {
        settingsDir = tmp.newFolder()
        h = AdminHarness(settingsDir)
        h.enrollAsBootstrapAdmin()
    }

    @After
    fun tearDown() = f.close()

    private suspend fun start(target: AddTarget): PendingEnrollment {
        h.serveCodeCreated()
        return (h.addDevice.start(target) as StartResult.Started).enrollment
    }

    private suspend fun usedDevice(flow: AddDeviceFlow, pending: PendingEnrollment) =
        (flow.poll(pending) as PollResult.Used).device

    private fun codeRequest(): JsonObject =
        Json.parseToJsonElement(f.fake.bodies("POST", "/v1/enrollment-codes").last()).jsonObject

    // ---- start: the request and the QR ----

    @Test
    fun aNewPersonCodeCarriesTheNameAndAdminFlagAndTheQrListsEveryAdmin() = runBlocking {
        val second = TestAdmin("11111111-1111-4111-8111-111111111111")
        h.storeRoster(2, listOf(h.self, second.device))
        val pending = start(AddTarget.NewPerson("  Alex ", makeAdmin = true))

        val req = codeRequest()
        assertEquals("Alex", req.getValue("display_name").jsonPrimitive.content)
        assertTrue(req.getValue("make_admin").jsonPrimitive.boolean)
        assertFalse("user_id" in req)

        val qr = EnrollmentQr.parse(pending.qrText, allowDevHttp = true)
        assertEquals(f.url, qr.url)
        assertArrayEquals(h.code, qr.code)
        assertEquals(32, qr.secret!!.size)
        assertEquals(setOf(h.selfId, second.id), qr.admins.map { it.id }.toSet())
        assertTrue(qr.admins.first { it.id == h.selfId }.ed25519.contentEquals(h.keys.ed25519Public))
    }

    @Test
    fun anotherPhoneForAPersonSendsOnlyTheUserIdAndExpectsTheRostersNameAndFlag() = runBlocking {
        val pending = start(AddTarget.ExistingUser(USER))
        val req = codeRequest()
        assertEquals(USER, req.getValue("user_id").jsonPrimitive.content)
        assertFalse("display_name" in req)
        assertFalse("make_admin" in req)

        val phone = NewPhone(userId = USER, displayName = "Sam", admin = true)
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        val flow = h.addDevice
        val done = flow.complete(pending, usedDevice(flow, pending)) as CompleteResult.Done
        assertEquals(USER, done.device.userId)
        assertEquals(2, h.roster(h.rosterPutBodies().single()).devices.size)
    }

    @Test
    fun refusalsHappenBeforeAnyNetworkCall() = runBlocking {
        val before = f.fake.requests.size
        val flow = h.addDevice
        h.locked = true
        assertEquals(
            AddDeviceError.LOCKED,
            (flow.start(AddTarget.NewPerson("Alex", false)) as StartResult.Failed).error
        )
        h.locked = false
        assertEquals(
            AddDeviceError.INVALID_NAME,
            (flow.start(AddTarget.NewPerson("   ", false)) as StartResult.Failed).error
        )
        assertEquals(
            AddDeviceError.INVALID_NAME,
            (flow.start(AddTarget.NewPerson("x".repeat(65), false)) as StartResult.Failed).error
        )
        assertEquals(
            AddDeviceError.UNKNOWN_USER,
            (flow.start(AddTarget.ExistingUser("22222222-2222-4222-8222-222222222222")) as StartResult.Failed).error
        )
        assertEquals(before, f.fake.requests.size)
    }

    @Test
    fun theQrTextRoundTripsThroughAZxingRenderAndDecode() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val matrix = QrCode.matrix(pending.qrText)
        val scale = 4
        val width = matrix.width * scale
        val pixels = IntArray(width * matrix.height * scale) { i ->
            val x = (i % width) / scale
            val y = (i / width) / scale
            if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(width, matrix.height * scale, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(pending.qrText, decoded.text)
        // Dark modules on a light quiet zone: the corner is light.
        assertFalse(matrix.get(0, 0))
    }

    // ---- poll ----

    @Test
    fun pollMapsEveryStatus() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val flow = h.addDevice
        h.serveCodeStatus("""{"status":"pending"}""")
        assertEquals(PollResult.Pending, flow.poll(pending))
        h.serveCodeStatus("""{"status":"expired"}""")
        assertEquals(PollResult.Expired, flow.poll(pending))
        f.fake.reply("GET", "/v1/enrollment-codes/$CODE_ID", 404, """{"error":"not_found"}""")
        assertEquals(PollResult.Expired, flow.poll(pending))
        f.fake.reply("GET", "/v1/enrollment-codes/$CODE_ID", 503)
        assertEquals(PollResult.Transient, flow.poll(pending))
        // A final 4xx can never succeed: terminal, not transient.
        f.fake.reply("GET", "/v1/enrollment-codes/$CODE_ID", 403, """{"error":"forbidden"}""")
        assertEquals(PollResult.ServerRefused, flow.poll(pending))
        f.fake.reply("GET", "/v1/enrollment-codes/$CODE_ID", 400, """{"error":"invalid_request"}""")
        assertEquals(PollResult.ServerRefused, flow.poll(pending))
        // A used code without a device, or an unknown status, is never accepted.
        h.serveCodeStatus("""{"status":"used"}""")
        assertEquals(EnrollmentRejection.MALFORMED, (flow.poll(pending) as PollResult.Rejected).reason)
        h.serveCodeStatus("""{"status":"weird"}""")
        assertEquals(EnrollmentRejection.MALFORMED, (flow.poll(pending) as PollResult.Rejected).reason)
        h.serveCodeStatus(
            """{"status":"used","device":{"id":"x","user_id":"y","display_name":"A","device_name":"B",""" +
                """"admin":false,"ed25519":"!!","x25519":"!!","mac":"!!"}}"""
        )
        assertEquals(EnrollmentRejection.MALFORMED, (flow.poll(pending) as PollResult.Rejected).reason)
    }

    @Test
    fun pollingStopsLocallyAtTheCodesExpiry() = runBlocking {
        h.serveCodeCreated(expiresAt = ISSUED_AT)
        val pending = (h.addDevice.start(AddTarget.NewPerson("Alex", false)) as StartResult.Started).enrollment
        h.serveCodeStatus("""{"status":"pending"}""")
        assertEquals(PollResult.Expired, h.addDevice.poll(pending))
        f.fake.reply("GET", "/v1/enrollment-codes/$CODE_ID", 503)
        assertEquals(PollResult.Expired, h.addDevice.poll(pending))
    }

    @Test
    fun aCodeUsedBeforeExpiryIsStillVerifiedAfterExpiresAtPassed() = runBlocking {
        h.serveCodeCreated(expiresAt = ISSUED_AT)
        val pending = (h.addDevice.start(AddTarget.NewPerson("Alex", false)) as StartResult.Started).enrollment
        val phone = NewPhone(userId = USER, displayName = "Sam", admin = false)
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        assertTrue(h.addDevice.poll(pending) is PollResult.Used)
    }

    @Test
    fun aPhoneThatIsNoLongerAnAdminCannotStartWithoutANetworkCall() = runBlocking {
        val second = TestAdmin("11111111-1111-4111-8111-111111111111")
        val demoted = RosterDevice(
            h.selfId,
            USER,
            "Sam",
            "Sam phone",
            false,
            h.keys.ed25519Public,
            h.keys.x25519Public
        )
        h.storeRoster(2, listOf(demoted, second.device))
        val before = f.fake.requests.size
        val result = h.addDevice.start(AddTarget.NewPerson("Alex", false)) as StartResult.Failed
        assertEquals(AddDeviceError.NOT_ADMIN, result.error)
        assertEquals(before, f.fake.requests.size)
    }

    // ---- happy path ----

    @Test
    fun happyPathPublishesVersionPlusOneSignedByThisAdminWithTheDeviceAsReturned() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val phone = NewPhone()
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        f.fake.reply("PUT", "/v1/roster", 204)
        val flow = h.addDevice

        val done = flow.complete(pending, usedDevice(flow, pending)) as CompleteResult.Done
        assertEquals(phone.id, done.device.id)

        val body = h.rosterPutBodies().single()
        val signed = SignedObject.fromWire(body)
        assertEquals(h.selfId, signed.signer)
        val sent = h.roster(body)
        assertEquals(2L, sent.version)
        assertEquals(listOf(h.selfId, phone.id).sorted(), sent.devices.map { it.id })
        assertEquals(phone.entry, sent.find(phone.id))
        // Local state is the same as a synced roster: the exact signed JSON, version 2.
        val stored = f.rosters.current()!!
        assertEquals(2L, stored.version)
        assertEquals(body, stored.signedJson)
    }

    @Test
    fun theSignedRosterVerifiesAgainstTheVersionOneRoster() = runBlocking {
        val v1 = Roster.parse(SignedObject.fromWire(f.rosters.current()!!.signedJson).payload)
        val pending = start(AddTarget.NewPerson("Alex", false))
        val phone = NewPhone()
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        f.fake.reply("PUT", "/v1/roster", 204)
        val flow = h.addDevice
        flow.complete(pending, usedDevice(flow, pending))

        val signed = SignedObject.fromWire(h.rosterPutBodies().single())
        val decision = RosterVerifier.accept(v1, emptyList(), null, signed)
        assertEquals(2L, (decision as RosterDecision.Accepted).roster.version)
    }

    // ---- rejections: nothing is signed ----

    private suspend fun assertRejected(
        reason: EnrollmentRejection,
        target: AddTarget,
        phone: NewPhone,
        secretFor: (ByteArray) -> ByteArray = { it },
        overrides: Map<String, String> = emptyMap()
    ) {
        val pending = start(target)
        h.serveCodeStatus(phone.usedJson(phone.mac(secretFor(h.secretOf(pending)), h.code), overrides))
        f.fake.reply("PUT", "/v1/roster", 204)
        val flow = h.addDevice
        val result = flow.complete(pending, usedDevice(flow, pending))
        assertEquals(reason, (result as CompleteResult.Rejected).reason)
        assertEquals("nothing may be signed or sent", 0, h.puts())
        assertEquals(1L, f.rosters.current()!!.version)
    }

    @Test
    fun aWrongMacIsRejected() = runBlocking {
        assertRejected(
            EnrollmentRejection.BAD_MAC,
            AddTarget.NewPerson("Alex", false),
            NewPhone(),
            secretFor = { ByteArray(32) { 1 } }
        )
    }

    @Test
    fun aTamperedKeyIsRejectedByTheMac() = runBlocking {
        val other = NewPhone()
        assertRejected(
            EnrollmentRejection.BAD_MAC,
            AddTarget.NewPerson("Alex", false),
            NewPhone(),
            overrides = mapOf("ed25519" to "\"${B64u.encode(other.keys.ed25519Public)}\"")
        )
    }

    @Test
    fun anAdminFlagThatWasNotAskedForIsRejected() = runBlocking {
        assertRejected(EnrollmentRejection.ADMIN_MISMATCH, AddTarget.NewPerson("Alex", false), NewPhone(admin = true))
    }

    @Test
    fun aDifferentNameIsRejected() = runBlocking {
        assertRejected(
            EnrollmentRejection.NAME_MISMATCH,
            AddTarget.NewPerson("Alex", false),
            NewPhone(displayName = "Mallory")
        )
    }

    @Test
    fun aDifferentUserIsRejected() = runBlocking {
        assertRejected(
            EnrollmentRejection.USER_MISMATCH,
            AddTarget.ExistingUser(USER),
            NewPhone(userId = ADMIN_USER, displayName = "Sam", admin = true)
        )
    }

    @Test
    fun aNewPersonWhoseUserIdIsAlreadyInTheRosterIsRejected() = runBlocking {
        assertRejected(
            EnrollmentRejection.USER_ALREADY_EXISTS,
            AddTarget.NewPerson("Sam", true),
            NewPhone(userId = USER, displayName = "Sam", admin = true)
        )
    }

    // ---- stale roster ----

    @Test
    fun staleSyncsThenReChecksAndReSignsExactlyOnce() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val phone = NewPhone()
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        // Another admin published version 3 meanwhile; the first PUT is stale, the second goes through.
        val second = TestAdmin("11111111-1111-4111-8111-111111111111")
        val newer = f.signedRoster(h.keys, h.selfId, listOf(h.self, second.device), 3)
        var puts = 0
        f.fake.on("PUT", "/v1/roster") {
            if (puts++ == 0) FakeServer.response(409, """{"error":"roster_stale"}""") else FakeServer.response(204)
        }
        f.serveRoster(newer)
        val flow = h.addDevice

        assertTrue(flow.complete(pending, usedDevice(flow, pending)) is CompleteResult.Done)

        assertEquals(2, h.puts())
        assertEquals(1, f.fake.count("GET", "/v1/roster"))
        val resigned = h.roster(h.rosterPutBodies().last())
        assertEquals(4L, resigned.version)
        assertEquals(setOf(h.selfId, second.id, phone.id), resigned.devices.map { it.id }.toSet())
        assertEquals(4L, f.rosters.current()!!.version)
    }

    @Test
    fun theReCheckAfterAStaleSyncCanStillRefuse() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val phone = NewPhone()
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        // The newer roster already lists a person with the new phone's user id.
        val clash = RosterDevice(
            "33333333-3333-4333-8333-333333333333",
            phone.userId,
            "Other",
            "Other phone",
            false,
            phone.keys.ed25519Public,
            phone.keys.x25519Public
        )
        f.fake.reply("PUT", "/v1/roster", 409, """{"error":"roster_stale"}""")
        f.serveRoster(f.signedRoster(h.keys, h.selfId, listOf(h.self, clash), 3))
        val flow = h.addDevice

        val result = flow.complete(pending, usedDevice(flow, pending))
        assertEquals(EnrollmentRejection.USER_ALREADY_EXISTS, (result as CompleteResult.Rejected).reason)
        assertEquals("only the first, stale PUT", 1, h.puts())
    }

    @Test
    fun aSecondStaleAnswerIsAnErrorThatCanBeRetried() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val phone = NewPhone()
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        f.fake.reply("PUT", "/v1/roster", 409, """{"error":"roster_stale"}""")
        f.serveRoster(f.signedRoster(h.keys, h.selfId, listOf(h.self), 3))
        val flow = h.addDevice
        val device = usedDevice(flow, pending)

        assertEquals(AddDeviceError.STALE, (flow.complete(pending, device) as CompleteResult.Failed).error)
        assertEquals(2, h.puts())

        // Retrying is allowed (it signs again) and can succeed.
        f.fake.reply("PUT", "/v1/roster", 204)
        assertTrue(flow.complete(pending, device) is CompleteResult.Done)
    }

    @Test
    fun aNetworkFailureWhilePublishingIsRetryableAndStoresNothing() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val phone = NewPhone()
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        f.fake.reply("PUT", "/v1/roster", 503)
        val flow = h.addDevice
        assertEquals(
            AddDeviceError.NETWORK,
            (flow.complete(pending, usedDevice(flow, pending)) as CompleteResult.Failed).error
        )
        assertEquals(1L, f.rosters.current()!!.version)
    }

    @Test
    fun noRosterIsStoredWhenTheServerRefusesIt() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val phone = NewPhone()
        h.serveCodeStatus(phone.usedJson(phone.mac(h.secretOf(pending), h.code)))
        f.fake.reply("PUT", "/v1/roster", 400, """{"error":"roster_invalid"}""")
        val flow = h.addDevice
        assertEquals(
            AddDeviceError.REJECTED_BY_SERVER,
            (flow.complete(pending, usedDevice(flow, pending)) as CompleteResult.Failed).error
        )
        assertEquals(1L, f.rosters.current()!!.version)
    }

    // ---- the secret is memory only ----

    @Test
    fun theSecretAndCodeAreInNoPersistedStoreAndNoLog() = runBlocking {
        val pending = start(AddTarget.NewPerson("Alex", false))
        val secret = h.secretOf(pending)
        val phone = NewPhone()
        h.serveCodeStatus(phone.usedJson(phone.mac(secret, h.code)))
        f.fake.reply("PUT", "/v1/roster", 204)
        val flow = h.addDevice
        flow.complete(pending, usedDevice(flow, pending))

        val needles = listOf(
            B64u.encode(secret),
            B64u.encode(h.code),
            pending.qrText,
            secret.joinToString("") { "%02x".format(it) },
            // The raw bytes, as they would appear in a binary store.
            String(secret, Charsets.ISO_8859_1)
        )
        val persisted = dataStoreText() + roomText() + f.logs.joinToString("\n")
        needles.forEach { assertFalse("persisted state leaks $it", persisted.contains(it)) }
        // The server-side PUT body is a signed roster: it carries public keys only.
        needles.forEach { n -> h.rosterPutBodies().forEach { assertFalse(it.contains(n)) } }

        pending.abandon()
        assertTrue(pending.secret.all { it == 0.toByte() })
        assertTrue(pending.code.all { it == 0.toByte() })
        assertFalse(pending.toString().contains(B64u.encode(h.code)))
    }

    private fun dataStoreText(): String = settingsDir.walkTopDown().filter { it.isFile }
        .joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun roomText(): String {
        val db = f.db.openHelper.readableDatabase
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
        }
        return tables.joinToString("\n") { dumpTable(db, it) }
    }

    private fun dumpTable(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): String =
        db.query("SELECT * FROM \"$table\"").use { rows ->
            generateSequence { if (rows.moveToNext()) rows else null }.joinToString("\n") { row ->
                (0 until row.columnCount).joinToString("\n") { i ->
                    if (row.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) {
                        String(row.getBlob(i), Charsets.ISO_8859_1)
                    } else {
                        row.getString(i).orEmpty()
                    }
                }
            }
        }
}
