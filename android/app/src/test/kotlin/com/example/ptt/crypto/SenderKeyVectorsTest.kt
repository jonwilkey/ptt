package com.example.ptt.crypto

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The `sender_keys` section of testdata/protocol-vectors.json, and the Kotlin side of the sender key. */
class SenderKeyVectorsTest {
    private fun b64(v: JsonValue.Obj, key: String) = VectorFile.b64(v, key)

    private fun section(name: String) = VectorFile.root.obj("sender_keys").array(name).map { it.asObject(name) }

    private fun hpkeKeys(privateKey: ByteArray, publicKey: ByteArray) = DeviceKeys.create(
        InMemoryDeviceKeyStore(presetHpke = InMemoryDeviceKeyStore.hpkeFromRaw(privateKey, publicKey))
    )

    @Test
    fun theSenderWrapFromGoOpensWithTinkAndOnlyWithTheSenderLabel() {
        val vectors = section("hpke")
        assertTrue(vectors.isNotEmpty())
        for (v in vectors) {
            val name = v.string("name")
            val page = v.string("page_id")
            val session = v.string("session_id")
            val device = v.string("device_id")
            val info = SenderKeys.info(page, session, device)
            assertArrayEquals(name, b64(v, "info"), info)
            val keys = hpkeKeys(b64(v, "recipient_private"), b64(v, "recipient_public"))
            val wrapped = b64(v, "wrapped")
            assertEquals(EnvelopePayload.WRAPPED_KEY_BYTES, wrapped.size)
            assertArrayEquals(name, b64(v, "session_key"), keys.unwrap(wrapped, info))
            val mine = TinkKeys.hpkeEncrypter(keys.x25519Public).encrypt(b64(v, "session_key"), info)
            assertArrayEquals(name, b64(v, "session_key"), keys.unwrap(mine, info))
            assertProtocolError { keys.unwrap(wrapped, PageEnvelopes.info(page, session, device)) }
            assertProtocolError { keys.unwrap(mine, PageEnvelopes.info(page, session, device)) }
        }
    }

    @Test
    fun everyRecoverScenarioEndsAtItsFirstFailingCheck() {
        val vectors = section("recover")
        assertEquals(18, vectors.size)
        for (v in vectors) {
            val name = v.string("name")
            val keys = hpkeKeys(b64(v, "self_private"), hpkePublicOf(v))
            val result = SenderKeys.recover(
                v.string("self_device_id"),
                b64(v, "self_ed25519"),
                keys,
                v.string("push_page_id"),
                SignedObject.fromWire(v.string("envelope")),
                SignedObject.fromWire(v.string("sender_key"))
            )
            if (v.string("expect") == "ok") {
                assertTrue("$name: rejected", result is RecoveryResult.Recovered)
                result as RecoveryResult.Recovered
                assertArrayEquals(name, b64(v, "session_key"), result.sessionKey)
                assertEquals(name, v.string("self_device_id"), result.envelope.fromDevice)
            } else {
                assertTrue("$name: accepted", result is RecoveryResult.Failed)
                assertEquals(name, v.string("reason"), (result as RecoveryResult.Failed).reason.name.lowercase())
            }
        }
    }

    @Test
    fun receiversRefuseASenderKeyWrapMovedIntoKeys() {
        val vectors = section("receive")
        assertEquals(2, vectors.size)
        for (v in vectors) {
            val name = v.string("name")
            val roster = Roster.parse(b64(v, "roster_payload"))
            val self = roster.find(v.string("receiver_device_id"))!!
            val keys = hpkeKeys(b64(v, "receiver_private"), self.x25519)
            val push = PagePush(v.string("push_page_id"), v.string("push_level"), v.string("push_from_user"))
            val result = PageEnvelopes.receive(roster, self.id, keys, SignedObject.fromWire(v.string("wire")), push)
            if (v.string("expect") == "ok") {
                assertArrayEquals(name, b64(v, "session_key"), (result as ReceiveResult.Accepted).sessionKey)
            } else {
                assertEquals(name, v.string("reason"), (result as ReceiveResult.Dropped).reason.name.lowercase())
            }
        }
    }

    private fun hpkePublicOf(recover: JsonValue.Obj): ByteArray {
        val wrap = section("hpke").single { it.string("recipient_private") == recover.string("self_private") }
        return b64(wrap, "recipient_public")
    }

    // ---- the payload ----

    private val page = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()
    private val device = UUID.randomUUID().toString()
    private val wrapped = B64u.encode(ByteArray(80) { it.toByte() })

    private fun payload(
        format: String = "1",
        pageId: String = page,
        extra: String = "",
        key: String? = wrapped
    ): ByteArray = (
        """{"format":$format,"page_id":"$pageId","session_id":"$session","from_device":"$device"""" +
            (if (key != null) ""","key":"$key"""" else "") + extra + "}"
        ).toByteArray()

    @Test
    fun aWellFormedPayloadParsesAndEncodesBackExactly() {
        val parsed = SenderKeyPayload.parse(payload())
        assertEquals(page, parsed.pageId)
        assertEquals(session, parsed.sessionId)
        assertEquals(device, parsed.fromDevice)
        assertEquals(80, parsed.key.size)
        assertArrayEquals(payload(), parsed.encode())
    }

    @Test
    fun theStrictPayloadRulesApply() {
        val bad = listOf(
            payload(format = "2"),
            payload(format = "\"1\""),
            payload(extra = ""","extra":true"""),
            payload(key = null),
            payload(pageId = page.uppercase()),
            payload(key = B64u.encode(ByteArray(79))),
            payload(key = B64u.encode(ByteArray(81))),
            payload(key = "$wrapped="),
            payload(extra = ""","key":"$wrapped""""),
            payload(extra = ""","from_device":null"""),
            "[]".toByteArray(),
            ByteArray(0)
        )
        for (p in bad) assertProtocolError { SenderKeyPayload.parse(p) }
    }

    // ---- build ----

    private val self = TestPhone()
    private val other = TestPhone()

    private class TestPhone {
        val id: String = UUID.randomUUID().toString()
        val keys: DeviceKeys = DeviceKeys.create(InMemoryDeviceKeyStore())
    }

    private fun built(): BuiltEnvelope {
        val user = UUID.randomUUID().toString()
        val roster = Roster(
            1,
            1_789_920_000_000L,
            listOf(
                RosterDevice(
                    self.id,
                    UUID.randomUUID().toString(),
                    "Alex",
                    "phone",
                    true,
                    self.keys.ed25519Public,
                    self.keys.x25519Public
                ),
                RosterDevice(other.id, user, "Sam", "phone", false, other.keys.ed25519Public, other.keys.x25519Public)
            ).sortedBy { it.id }
        )
        return PageEnvelopes.build(roster, self.id, self.keys, user, page, session, "normal", 1_789_920_000_000L)
    }

    @Test
    fun theBuiltSenderKeyRecoversTheSessionKeyAndStaysOutOfTheEnvelope() {
        val b = built()
        assertEquals(setOf(other.id), b.payload.keys.keys)
        assertEquals(self.id, b.senderKey.signer)
        val result = SenderKeys.recover(self.id, self.keys.ed25519Public, self.keys, page, b.signed, b.senderKey)
        result as RecoveryResult.Recovered
        assertArrayEquals(b.sessionKey, result.sessionKey)
        assertEquals(session, result.envelope.sessionId)
        val onWire = SignedObject.fromWire(b.senderKey.toWire())
        SenderKeyPayload.parse(onWire.payload)
        assertTrue(
            TinkKeys.verifyEd25519(
                self.keys.ed25519Public,
                SignedObject.message(Contexts.SENDER_KEY, onWire.payload),
                onWire.sig
            )
        )
    }

    @Test
    fun anotherDeviceCannotRecoverAndAReceiverCannotOpenTheSenderKey() {
        val b = built()
        val result = SenderKeys.recover(other.id, other.keys.ed25519Public, other.keys, page, b.signed, b.senderKey)
        assertEquals(RecoveryFailure.ENVELOPE_NOT_OWN, (result as RecoveryResult.Failed).reason)
        val wrap = SenderKeyPayload.parse(b.senderKey.payload).key
        assertProtocolError { other.keys.unwrap(wrap, SenderKeys.info(page, session, self.id)) }
        assertProtocolError { self.keys.unwrap(wrap, PageEnvelopes.info(page, session, self.id)) }
    }

    private fun assertProtocolError(block: () -> Unit) {
        try {
            block()
            fail("expected a ProtocolException")
        } catch (_: ProtocolException) {
            // expected
        }
    }
}
