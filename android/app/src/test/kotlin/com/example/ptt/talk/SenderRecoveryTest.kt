package com.example.ptt.talk

import com.example.ptt.crypto.BuiltEnvelope
import com.example.ptt.crypto.Contexts
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.InMemoryDeviceKeyStore
import com.example.ptt.crypto.PageEnvelopes
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDevice
import com.example.ptt.crypto.SenderKeyPayload
import com.example.ptt.crypto.SenderKeys
import com.example.ptt.crypto.SignedObject
import com.example.ptt.crypto.TinkKeys
import com.example.ptt.net.ApiResult
import com.example.ptt.net.InMemoryDeviceTokenStore
import com.example.ptt.net.NotEnrolledReason
import com.example.ptt.net.PageRecoveryResponse
import com.example.ptt.outbox.FakePttApi
import com.example.ptt.push.SessionKeys
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val SELF_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val USER_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val OTHER_USER = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val PAGE = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private const val SESSION = "ffffffff-ffff-4fff-8fff-ffffffffffff"
private const val SESSION_2 = "11111111-1111-4111-8111-111111111111"
private const val NOW = 1_789_920_000_000L

@RunWith(RobolectricTestRunner::class)
class SenderRecoveryTest {
    private val store = InMemoryDeviceKeyStore()
    private val own = DeviceKeys.create(store)
    private val peer = DeviceKeys.create(InMemoryDeviceKeyStore())
    private val stranger = DeviceKeys.create(InMemoryDeviceKeyStore())
    private val api = FakePttApi()
    private val keys = SessionKeys()
    private val roster = Roster(
        1,
        NOW,
        listOf(
            RosterDevice(SELF_ID, USER_ID, "Alex", "phone", true, own.ed25519Public, own.x25519Public),
            RosterDevice(OTHER_ID, OTHER_USER, "Sam", "phone", false, peer.ed25519Public, peer.x25519Public)
        )
    )

    private fun recovery(tokens: InMemoryDeviceTokenStore = InMemoryDeviceTokenStore("t", SELF_ID)) =
        SenderRecovery(api, store, tokens, keys)

    private fun build(session: String = SESSION, page: String = PAGE) =
        PageEnvelopes.build(roster, SELF_ID, own, OTHER_USER, page, session, "normal", NOW)

    private fun serve(
        b: BuiltEnvelope = build(),
        senderKey: SignedObject = b.senderKey,
        envelope: SignedObject = b.signed
    ) {
        api.pageResult = ApiResult.Success(PageRecoveryResponse(envelope, senderKey))
    }

    private fun recover(r: SenderRecovery = recovery()) = runBlocking { r.recover(PAGE) }

    private fun assertNothingKept() {
        assertFalse(keys.has(PAGE))
        assertNull(keys.senderKey(PAGE))
        assertTrue(keys.peers(PAGE).isEmpty())
    }

    @Test(timeout = 60_000)
    fun aGoodPageGivesTheKeyThePeersAndTheSenderKeyBackInMemory() {
        val b = build()
        serve(b)
        assertEquals(SESSION, recover())
        assertEquals(listOf("get:$PAGE"), api.calls)
        assertArrayEquals(b.sessionKey, keys.get(PAGE))
        assertEquals(setOf(OTHER_ID), keys.peers(PAGE))
        assertSame(b.senderKey, keys.senderKey(PAGE))
    }

    @Test(timeout = 60_000)
    fun theSendersOwnDeviceIsNeverAPeer() {
        val self = RosterDevice(SELF_ID, USER_ID, "Alex", "phone", true, own.ed25519Public, own.x25519Public)
        val twoWay = Roster(
            1,
            NOW,
            listOf(
                self,
                roster.devices.last().let {
                    RosterDevice(it.id, USER_ID, it.displayName, it.deviceName, false, it.ed25519, it.x25519)
                }
            )
        )
        val b = PageEnvelopes.build(twoWay, SELF_ID, own, USER_ID, PAGE, SESSION, "normal", NOW)
        assertEquals(setOf(SELF_ID, OTHER_ID), b.payload.keys.keys)
        serve(b)
        assertEquals(SESSION, recover())
        assertEquals(setOf(OTHER_ID), keys.peers(PAGE))
    }

    @Test(timeout = 60_000)
    fun everyNon200AndEveryNetworkFailureIsAFailure() {
        val failures = listOf(
            ApiResult.Final(404, "not_found"),
            ApiResult.Final(410, "page_ended"),
            ApiResult.Final(403, "forbidden"),
            ApiResult.Final(200, "bad_response"),
            ApiResult.Final(401, "unauthorized"),
            ApiResult.Retryable(503),
            ApiResult.Retryable(null, cause = IOException("offline")),
            ApiResult.NotEnrolled(NotEnrolledReason.NOT_ENROLLED)
        )
        for (failure in failures) {
            api.pageResult = failure
            assertNull("$failure", recover())
            assertNothingKept()
        }
        assertEquals("one request each, no retry", failures.size, api.calls.size)
    }

    @Test(timeout = 60_000)
    fun aTamperedEnvelopeOrSenderKeyIsRefusedWithNothingKept() {
        val b = build()
        val badSig = { o: SignedObject ->
            SignedObject(
                o.payload,
                o.signer,
                o.sig.copyOf().also {
                    it[0] =
                        (it[0] + 1).toByte()
                }
            )
        }
        val cases = mapOf(
            "envelope signature" to { serve(b, envelope = badSig(b.signed)) },
            "sender key signature" to { serve(b, senderKey = badSig(b.senderKey)) },
            "someone else's sender key" to {
                serve(b, senderKey = SignedObject(b.senderKey.payload, OTHER_ID, b.senderKey.sig))
            },
            "another session's sender key" to { serve(b, senderKey = build(session = SESSION_2).senderKey) },
            "another page's envelope" to { serve(build(page = OTHER_ID)) }
        )
        for ((name, setUp) in cases) {
            setUp()
            assertNull(name, recover())
            assertNothingKept()
        }
    }

    @Test(timeout = 60_000)
    fun aKeyWrappedForAnotherDeviceFailsToOpen() {
        val b = build()
        val wrongWrap = TinkKeys.hpkeEncrypter(stranger.x25519Public)
            .encrypt(b.sessionKey, SenderKeys.info(PAGE, SESSION, SELF_ID))
        val payload = SenderKeyPayload(PAGE, SESSION, SELF_ID, wrongWrap)
        val signed = own.signObject(Contexts.SENDER_KEY, SELF_ID, payload.encode())
        serve(b, senderKey = signed)
        assertNull(recover())
        assertNothingKept()
    }

    @Test(timeout = 60_000)
    fun withoutAnIdentityOrKeysThereIsNothingToAsk() {
        serve()
        assertNull(recover(recovery(InMemoryDeviceTokenStore("t", null))))
        assertTrue(api.calls.isEmpty())
        val noKeys = SenderRecovery(api, InMemoryDeviceKeyStore(), InMemoryDeviceTokenStore("t", SELF_ID), keys)
        assertNull(recover(noKeys))
        assertTrue(api.calls.isEmpty())
    }
}
