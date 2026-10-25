package com.example.ptt.crypto

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Behaviour that needs no vectors: round trips and the rejection list of the spec's "Testing" section. */
class CryptoRoundTripTest {
    private class Phone(val userId: String, val name: String, admin: Boolean) {
        val id: String = UUID.randomUUID().toString()
        val keys = DeviceKeys.create(InMemoryDeviceKeyStore())
        val entry = RosterDevice(id, userId, name, "$name's phone", admin, keys.ed25519Public, keys.x25519Public)
        val identity = DeviceIdentity(id, keys.ed25519Public, keys.x25519Public)
        val trusted = TrustedAdmin(id, keys.ed25519Public)
    }

    private val alexUser = UUID.randomUUID().toString()
    private val samUser = UUID.randomUUID().toString()
    private val alex = Phone(alexUser, "Alex", true)
    private val sam = Phone(samUser, "Sam", false)
    private val sam2 = Phone(samUser, "Sam", false)
    private val now = 1_789_920_000_000L

    private fun family(): Roster {
        val r1 = RosterEditor.bootstrap(alex.entry, now)
        val r2 = RosterEditor.addDevice(r1, sam.entry, now + 1)
        return RosterEditor.addDevice(r2, sam2.entry, now + 2)
    }

    private fun accepted(d: RosterDecision): Roster {
        assertTrue("expected acceptance", d is RosterDecision.Accepted)
        return (d as RosterDecision.Accepted).roster
    }

    private fun rejected(d: RosterDecision): RosterRejection {
        assertTrue("expected rejection", d is RosterDecision.Rejected)
        return (d as RosterDecision.Rejected).reason
    }

    private fun assertProtocolError(block: () -> Unit) {
        try {
            block()
            fail("expected a ProtocolException")
        } catch (_: ProtocolException) {
            // expected
        }
    }

    @Test
    fun bootstrapRosterIsAcceptedBySelf() {
        val r1 = RosterEditor.bootstrap(alex.entry, now)
        val signed = RosterEditor.sign(r1, alex.id, alex.keys)
        val got = accepted(RosterVerifier.accept(null, listOf(alex.trusted), alex.identity, signed))
        assertEquals(1, got.version)
    }

    @Test
    fun editedRosterIsSortedVersionedAndAcceptedByCurrentAdmin() {
        val r1 = RosterEditor.bootstrap(alex.entry, now)
        val r3 = family()
        assertEquals(3, r3.version)
        assertEquals(r3.devices.map { it.id }.sorted(), r3.devices.map { it.id })
        val signed = RosterEditor.sign(r3, alex.id, alex.keys)
        accepted(RosterVerifier.accept(r1, emptyList(), null, signed))
        // Re-parsing the signed bytes gives the same roster.
        val parsed = Roster.parse(SignedObject.fromWire(signed.toWire()).payload)
        assertEquals(r3.devices.size, parsed.devices.size)
        val removed = RosterEditor.removeDevice(r3, sam2.id, now + 3)
        assertEquals(4, removed.version)
        assertEquals(null, removed.find(sam2.id))
    }

    @Test
    fun removingTheLastAdminOrDuplicatingIsRefused() {
        assertProtocolError { RosterEditor.removeDevice(family(), alex.id, now + 3) }
        assertProtocolError { RosterEditor.addDevice(family(), sam.entry, now + 3) }
        assertProtocolError { RosterEditor.removeDevice(family(), UUID.randomUUID().toString(), now + 3) }
        assertProtocolError { RosterEditor.sign(family(), sam.id, sam.keys) }
    }

    @Test
    fun rolledBackOrEqualVersionIsRejected() {
        val r3 = family()
        val old = RosterEditor.sign(RosterEditor.bootstrap(alex.entry, now), alex.id, alex.keys)
        assertEquals(RosterRejection.STALE_VERSION, rejected(RosterVerifier.accept(r3, emptyList(), null, old)))
        val same = RosterEditor.sign(r3, alex.id, alex.keys)
        assertEquals(RosterRejection.STALE_VERSION, rejected(RosterVerifier.accept(r3, emptyList(), null, same)))
    }

    @Test
    fun rosterSignedByNonAdminOrUnknownOrTamperedIsRejected() {
        val current = family()
        val next = RosterEditor.removeDevice(current, sam2.id, now + 3)
        // Signed by a non-admin member of the roster (sign() refuses, so sign by hand).
        val byNonAdmin = sam.keys.signObject(Contexts.ROSTER, sam.id, next.encodePayload())
        assertEquals(
            RosterRejection.UNTRUSTED_SIGNER,
            rejected(RosterVerifier.accept(current, emptyList(), null, byNonAdmin))
        )
        val stranger = Phone(UUID.randomUUID().toString(), "Eve", true)
        val byStranger = stranger.keys.signObject(Contexts.ROSTER, stranger.id, next.encodePayload())
        assertEquals(
            RosterRejection.UNTRUSTED_SIGNER,
            rejected(RosterVerifier.accept(current, emptyList(), null, byStranger))
        )
        // Stranger signature under an admin's id.
        val forged =
            SignedObject(next.encodePayload(), alex.id, stranger.keys.sign(Contexts.ROSTER, next.encodePayload()))
        assertEquals(RosterRejection.BAD_SIGNATURE, rejected(RosterVerifier.accept(current, emptyList(), null, forged)))
        val good = RosterEditor.sign(next, alex.id, alex.keys)
        val tamperedPayload = good.payload.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertEquals(
            RosterRejection.BAD_SIGNATURE,
            rejected(
                RosterVerifier.accept(current, emptyList(), null, SignedObject(tamperedPayload, good.signer, good.sig))
            )
        )
        val tamperedSig = good.sig.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertEquals(
            RosterRejection.BAD_SIGNATURE,
            rejected(
                RosterVerifier.accept(current, emptyList(), null, SignedObject(good.payload, good.signer, tamperedSig))
            )
        )
        // Signed with the envelope context instead.
        val wrongCtx = alex.keys.signObject(Contexts.ENVELOPE, alex.id, next.encodePayload())
        assertEquals(
            RosterRejection.BAD_SIGNATURE,
            rejected(RosterVerifier.accept(current, emptyList(), null, wrongCtx))
        )
    }

    @Test
    fun validSignatureOverMalformedPayloadIsRejectedNotCrashed() {
        val payload = """{"format":1,"version":9,"issued_at":1,"devices":[],"extra":null}""".toByteArray()
        val signed = alex.keys.signObject(Contexts.ROSTER, alex.id, payload)
        assertEquals(
            RosterRejection.MALFORMED_PAYLOAD,
            rejected(RosterVerifier.accept(family(), emptyList(), null, signed))
        )
    }

    @Test
    fun firstRosterAfterEnrollmentNeedsQrAdminAndSelf() {
        val roster = family()
        val signed = RosterEditor.sign(roster, alex.id, alex.keys)
        accepted(RosterVerifier.accept(null, listOf(alex.trusted), sam.identity, signed))
        // Signer not among the QR admins.
        val other = TrustedAdmin(alex.id, sam.keys.ed25519Public)
        assertEquals(
            RosterRejection.BAD_SIGNATURE,
            rejected(RosterVerifier.accept(null, listOf(other), sam.identity, signed))
        )
        assertEquals(
            RosterRejection.UNTRUSTED_SIGNER,
            rejected(RosterVerifier.accept(null, listOf(sam.trusted), sam.identity, signed))
        )
        // This device is missing, or listed with different keys.
        val newcomer = Phone(samUser, "Sam", false)
        assertEquals(
            RosterRejection.SELF_NOT_LISTED,
            rejected(RosterVerifier.accept(null, listOf(alex.trusted), newcomer.identity, signed))
        )
        val wrongKeys = DeviceIdentity(sam.id, sam.keys.ed25519Public, sam2.keys.x25519Public)
        assertEquals(
            RosterRejection.SELF_NOT_LISTED,
            rejected(RosterVerifier.accept(null, listOf(alex.trusted), wrongKeys, signed))
        )
        assertEquals(
            RosterRejection.NO_TRUST_ANCHOR,
            rejected(RosterVerifier.accept(null, emptyList(), sam.identity, signed))
        )
        assertEquals(
            RosterRejection.NO_TRUST_ANCHOR,
            rejected(RosterVerifier.accept(null, listOf(alex.trusted), null, signed))
        )
        // A roster that lists the QR admin as a non-admin, or with different keys, is refused.
        val demoted = Roster(
            roster.version,
            roster.issuedAt,
            roster.devices.map {
                if (it.id ==
                    alex.id
                ) {
                    RosterDevice(it.id, it.userId, it.displayName, it.deviceName, false, it.ed25519, it.x25519)
                } else {
                    it
                }
            }
        )
        // (the roster still needs an admin to be well formed: promote sam2 in the copy)
        val demotedWithAdmin = Roster(
            demoted.version,
            demoted.issuedAt,
            demoted.devices.map {
                if (it.id ==
                    sam2.id
                ) {
                    RosterDevice(it.id, it.userId, it.displayName, it.deviceName, true, it.ed25519, it.x25519)
                } else {
                    it
                }
            }
        )
        val bySigner = alex.keys.signObject(Contexts.ROSTER, alex.id, demotedWithAdmin.encodePayload())
        assertEquals(
            RosterRejection.SIGNER_NOT_LISTED,
            rejected(RosterVerifier.accept(null, listOf(alex.trusted), sam.identity, bySigner))
        )
    }

    @Test
    fun enrollmentCheckRefusesWhatTheServerMayLieAbout() {
        val secret = ByteArray(32) { it.toByte() }
        val code = ByteArray(16) { (it + 100).toByte() }
        val newcomer = Phone(UUID.randomUUID().toString(), "Kit", false)
        fun response(
            userId: String = newcomer.userId,
            name: String = "Kit",
            admin: Boolean = false,
            tamper: Boolean = false
        ): EnrolledDevice {
            val mac = EnrollmentMac.compute(
                secret,
                code,
                newcomer.id,
                newcomer.keys.ed25519Public,
                newcomer.keys.x25519Public
            )
            if (tamper) mac[0] = (mac[0] + 1).toByte()
            return EnrolledDevice(
                RosterDevice(
                    newcomer.id,
                    userId,
                    name,
                    "Kit's phone",
                    admin,
                    newcomer.keys.ed25519Public,
                    newcomer.keys.x25519Public
                ),
                mac
            )
        }
        val request = EnrollmentRequest("Kit", false, null)
        val current = family()
        val ok = AdminEnrollment.check(secret, code, request, response(), current)
        assertTrue(ok is EnrollmentCheck.Accepted)
        fun reason(c: EnrollmentCheck) = (c as EnrollmentCheck.Rejected).reason
        assertEquals(
            EnrollmentRejection.BAD_MAC,
            reason(AdminEnrollment.check(secret, code, request, response(tamper = true), current))
        )
        assertEquals(
            EnrollmentRejection.ADMIN_MISMATCH,
            reason(AdminEnrollment.check(secret, code, request, response(admin = true), current))
        )
        assertEquals(
            EnrollmentRejection.NAME_MISMATCH,
            reason(AdminEnrollment.check(secret, code, request, response(name = "Sam"), current))
        )
        // A new person must not reuse an existing user id.
        assertEquals(
            EnrollmentRejection.USER_ALREADY_EXISTS,
            reason(AdminEnrollment.check(secret, code, request, response(userId = samUser), current))
        )
        // An existing person must keep the requested user id.
        val forSam = EnrollmentRequest("Kit", false, samUser)
        assertTrue(
            AdminEnrollment.check(secret, code, forSam, response(userId = samUser), current) is EnrollmentCheck.Accepted
        )
        assertEquals(
            EnrollmentRejection.USER_MISMATCH,
            reason(AdminEnrollment.check(secret, code, forSam, response(userId = alexUser), current))
        )
        // MAC computed with another secret.
        assertEquals(
            EnrollmentRejection.BAD_MAC,
            reason(AdminEnrollment.check(ByteArray(32), code, request, response(), current))
        )
    }

    @Test
    fun qrParserRejectsMalformedText() {
        val good = EnrollmentQr("https://ptt.example.com", ByteArray(16), ByteArray(32), listOf(alex.trusted)).encode()
        EnrollmentQr.parse(good)
        val body = good.removePrefix(EnrollmentQr.PREFIX)
        fun enc(json: String) = EnrollmentQr.PREFIX + B64u.encode(json.toByteArray())
        val code = B64u.encode(ByteArray(16))
        val secret = B64u.encode(ByteArray(32))
        val admin = """{"id":"${alex.id}","ed25519":"${B64u.encode(alex.keys.ed25519Public)}"}"""
        for (bad in listOf(
            body,
            "pttenroll2:$body",
            good + "=",
            good.replace('-', '+').replace("_", "/") + "!",
            enc("""{"url":"u","code":"$code","code":"$code"}"""),
            enc("""{"url":"u","code":"$code","extra":1}"""),
            enc("""{"url":"u","code":"${B64u.encode(ByteArray(15))}"}"""),
            enc("""{"url":"u","code":"$code","secret":"$secret"}"""),
            enc("""{"url":"u","code":"$code","secret":"$secret","admins":[]}"""),
            enc("""{"url":"u","code":"$code","secret":"$secret","admins":[{"id":"X","ed25519":"$secret"}]}"""),
            enc("""{"url":null,"code":"$code"}"""),
            enc("""{"url":"u","code":"$code","secret":"$secret","admins":[$admin,null]}"""),
            enc("""{"URL":"u","code":"$code"}""")
        )) {
            assertProtocolError { EnrollmentQr.parse(bad) }
        }
    }

    @Test
    fun unsignedRosterIsRejected() {
        val payload = RosterEditor.bootstrap(alex.entry, now).encodePayload()
        // A bare payload (what an unsigned roster looks like) is not a signed object.
        assertProtocolError { SignedObject.fromWire(payload) }
        val signed = RosterEditor.sign(RosterEditor.bootstrap(alex.entry, now), alex.id, alex.keys)
        // A wire object without a sig.
        val noSig = """{"payload":"${B64u.encode(signed.payload)}","signer":"${alex.id}"}"""
        assertProtocolError { SignedObject.fromWire(noSig) }
        // An all-zero signature decodes but never verifies.
        val zero = SignedObject(signed.payload, alex.id, ByteArray(SignedObject.SIGNATURE_BYTES))
        assertEquals(
            RosterRejection.BAD_SIGNATURE,
            rejected(RosterVerifier.accept(null, listOf(alex.trusted), alex.identity, zero))
        )
        assertEquals(
            RosterRejection.BAD_SIGNATURE,
            rejected(RosterVerifier.accept(family(), emptyList(), null, zero))
        )
    }

    @Test
    fun loadNeverGeneratesAndCreateRefusesToOverwrite() {
        val store = InMemoryDeviceKeyStore()
        try {
            DeviceKeys.load(store)
            fail("load() generated or found keys")
        } catch (_: DeviceKeysUnavailable) {
            // expected: a missing keyset is a typed failure, not a new identity
        }
        val created = DeviceKeys.create(store)
        assertArrayEquals(created.ed25519Public, DeviceKeys.load(store).ed25519Public)
        assertArrayEquals(created.x25519Public, DeviceKeys.load(store).x25519Public)
        try {
            DeviceKeys.create(store)
            fail("create() overwrote existing keys")
        } catch (_: IllegalStateException) {
            // expected
        }
        store.wipe()
        try {
            DeviceKeys.load(store)
            fail("keys survived wipe")
        } catch (_: DeviceKeysUnavailable) {
            // expected
        }
    }

    @Test
    fun qrUrlMustBeHttps() {
        fun qr(url: String) = EnrollmentQr(url, ByteArray(16), null, emptyList()).encode()
        EnrollmentQr.parse(qr("https://ptt.example.com"))
        for (bad in listOf(
            "http://ptt.example.com", "ptt.example.com", "https://", "ftp://x", "javascript:alert(1)", "",
            "https://a b", "HTTP://ptt.example.com", "http://localhost.evil.example"
        )) {
            assertProtocolError { EnrollmentQr.parse(qr(bad)) }
            assertProtocolError { EnrollmentQr.parse(qr(bad), allowDevHttp = true) }
        }
        for (dev in listOf("http://10.0.2.2:8080", "http://localhost", "http://localhost:8080/x")) {
            assertProtocolError { EnrollmentQr.parse(qr(dev)) }
            EnrollmentQr.parse(qr(dev), allowDevHttp = true)
        }
    }

    private fun buildPage(roster: Roster = family(), level: String = "normal") = PageEnvelopes.build(
        roster,
        alex.id,
        alex.keys,
        samUser,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        level,
        now
    )

    private fun pushFor(b: BuiltEnvelope) = PagePush(b.payload.pageId, b.payload.level, alexUser)

    @Test
    fun envelopeRoundTripReachesEveryRecipientDeviceOnly() {
        val roster = family()
        val built = buildPage(roster)
        assertEquals(setOf(sam.id, sam2.id), built.payload.keys.keys)
        assertTrue(built.payload.keys.values.all { it.size == 80 })
        assertEquals(32, built.sessionKey.size)
        val wire = SignedObject.fromWire(built.signed.toWire())
        for (phone in listOf(sam, sam2)) {
            val result = PageEnvelopes.receive(roster, phone.id, phone.keys, wire, pushFor(built))
            assertTrue(result is ReceiveResult.Accepted)
            result as ReceiveResult.Accepted
            assertArrayEquals(built.sessionKey, result.sessionKey)
            assertEquals("Alex", result.sender.displayName)
        }
        // A device of another user gets nothing.
        val result = PageEnvelopes.receive(roster, alex.id, alex.keys, wire, pushFor(built))
        assertEquals(DropReason.NOT_FOR_THIS_USER, (result as ReceiveResult.Dropped).reason)
        // Two builds never share a session key.
        assertFalse(buildPage(roster).sessionKey.contentEquals(built.sessionKey))
    }

    private fun drop(r: ReceiveResult): DropReason = (r as ReceiveResult.Dropped).reason

    @Test
    fun tamperedOrMisaddressedEnvelopesAreDropped() {
        val roster = family()
        val built = buildPage(roster)
        val s = built.signed
        val push = pushFor(built)
        fun recv(o: SignedObject, p: PagePush = push, who: Phone = sam, keys: DeviceKeys = who.keys) =
            PageEnvelopes.receive(roster, who.id, keys, o, p)
        assertEquals(
            DropReason.BAD_SIGNATURE,
            drop(recv(SignedObject(s.payload.copyOf().also { it[10] = (it[10] + 1).toByte() }, s.signer, s.sig)))
        )
        assertEquals(
            DropReason.BAD_SIGNATURE,
            drop(recv(SignedObject(s.payload, s.signer, s.sig.copyOf().also { it[5] = (it[5] + 1).toByte() })))
        )
        assertEquals(
            DropReason.PAGE_ID_MISMATCH,
            drop(recv(s, PagePush(UUID.randomUUID().toString(), "normal", alexUser)))
        )
        assertEquals(DropReason.LEVEL_MISMATCH, drop(recv(s, PagePush(push.pageId, "urgent", alexUser))))
        assertEquals(DropReason.FROM_USER_MISMATCH, drop(recv(s, PagePush(push.pageId, "normal", samUser))))
        // Wrong HPKE key: another device's keys cannot open this device's entry.
        assertEquals(DropReason.DECRYPT_FAILED, drop(recv(s, keys = sam2.keys)))
        // Signed by an unknown device.
        val eve = Phone(UUID.randomUUID().toString(), "Eve", true)
        val forged = eve.keys.signObject(Contexts.ENVELOPE, eve.id, s.payload)
        assertEquals(DropReason.UNKNOWN_SIGNER, drop(recv(forged)))
        // Signed with the roster context.
        val wrongCtx = alex.keys.signObject(Contexts.ROSTER, alex.id, s.payload)
        assertEquals(DropReason.BAD_SIGNATURE, drop(recv(wrongCtx)))
        // Roster entry for the signer is a different key than the one that signed.
        val impostor = eve.keys.signObject(Contexts.ENVELOPE, alex.id, s.payload)
        assertEquals(DropReason.BAD_SIGNATURE, drop(recv(impostor)))
    }

    @Test
    fun senderMustBeInRosterAndRecipientsMustExist() {
        val roster = family()
        val eve = Phone(UUID.randomUUID().toString(), "Eve", true)
        assertProtocolError {
            PageEnvelopes.build(
                roster,
                eve.id,
                eve.keys,
                samUser,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "normal",
                now
            )
        }
        assertProtocolError {
            PageEnvelopes.build(
                roster,
                alex.id,
                alex.keys,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "normal",
                now
            )
        }
        assertProtocolError { buildPage(roster, level = "loud") }
    }

    @Test
    fun signedObjectKeysAreNotLeakedInWire() {
        val built = buildPage()
        assertNotNull(built.signed.toWire())
        assertFalse(built.signed.toWire().contains(B64u.encode(built.sessionKey)))
    }

    @Test
    fun strictJsonAndB64uCornerCases() {
        for (bad in listOf(
            """{"a":1,"a":2}""", "{\"a\":1,\"\\u0061\":2}", """{"a":null}""",
            "{\"a\":\"\\ud800\"}",
            """{"a":1.5}""", """{"a":01}""", """{"a":"x"} {}""", "{\"a\":\"\t\"}", """{"a":[}""", ""
        )) {
            assertProtocolError { StrictJson.parse(bad) }
        }
        assertProtocolError {
            StrictJson.parse(
                byteArrayOf(
                    '{'.code.toByte(),
                    '"'.code.toByte(),
                    0xff.toByte(),
                    '"'.code.toByte(),
                    ':'.code.toByte(),
                    '1'.code.toByte(),
                    '}'.code.toByte()
                )
            )
        }
        assertProtocolError { StrictJson.parse("[".repeat(100)) }
        val ok = StrictJson.parse("""{"a":"\ud83c\udf89","b":[1,true,{"c":-0}]}""") as JsonValue.Obj
        assertEquals("🎉", ok.string("a"))
        for (bad in listOf("AA==", "AA=", "A", "AB", "AAB", "+/+/", "AA\n", " AA", "AAA!")) {
            assertProtocolError { B64u.decode(bad) }
        }
        assertEquals("", B64u.encode(ByteArray(0)))
        for (n in 0..40) {
            val data = ByteArray(n) { (it * 37 + n).toByte() }
            assertArrayEquals(data, B64u.decode(B64u.encode(data)))
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data), B64u.encode(data))
        }
    }
}
