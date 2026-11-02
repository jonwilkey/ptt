package com.example.ptt.talk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ptt.crypto.AudioSession
import com.example.ptt.crypto.GrantOutcome
import com.example.ptt.crypto.SignedObject
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.TestDb
import com.example.ptt.net.ClientMessage
import com.example.ptt.net.CloseReaction
import com.example.ptt.net.FakeTalkAuth
import com.example.ptt.net.FakeTalkTransport
import com.example.ptt.net.FakeWs
import com.example.ptt.net.InMemoryDeviceTokenStore
import com.example.ptt.net.OWN
import com.example.ptt.net.PEER_DEVICE
import com.example.ptt.net.RttSamples
import com.example.ptt.net.SessionEndReason
import com.example.ptt.net.TalkCodec
import com.example.ptt.net.TalkSocket
import com.example.ptt.net.WS_TOKEN
import com.example.ptt.net.WsPageState
import com.example.ptt.net.serverHello
import com.example.ptt.outbox.Backoff
import com.example.ptt.outbox.OutboxOutcome
import com.example.ptt.push.SelfPages
import com.example.ptt.push.SessionKeys
import com.example.ptt.push.SocketPageEvents
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val PAGE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val SESSION = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val STRANGER = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private val KEY = ByteArray(32) { (it + 1).toByte() }
private val SENDER_KEY = SignedObject(ByteArray(210) { (it + 7).toByte() }, OWN, ByteArray(64) { 5 })

private class FakeSink : SocketPageEvents {
    val states = mutableListOf<WsPageState>()
    val ended = mutableListOf<Pair<PageRole, SessionEndReason>>()

    override suspend fun socketPageState(pageId: String, state: WsPageState) {
        states += state
    }

    override suspend fun sessionEnded(pageId: String, role: PageRole, reason: SessionEndReason) {
        ended += role to reason
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass")
class TalkSessionControllerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: PttDatabase
    private val transport = FakeTalkTransport()
    private val auth = FakeTalkAuth()
    private val keys = SessionKeys()
    private val sink = FakeSink()
    private val selfPages = SelfPages()
    private val outcomes = MutableSharedFlow<OutboxOutcome>(extraBufferCapacity = 16)
    private var serviceStops = 0
    private val stoppedPages = mutableListOf<String>()
    private val tokens = InMemoryDeviceTokenStore(WS_TOKEN, OWN)
    private var recover: suspend (String) -> String? = { null }
    private val recoveryCalls = mutableListOf<String>()

    @Before
    fun setUp() {
        db = TestDb.inMemory(app)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private inner class Rig(val test: TestScope) {
        val repo = com.example.ptt.data.ActivePageRepository(db.activePageDao(), Clock.systemUTC())
        val controller = TalkSessionController(
            transport,
            auth,
            "0.3.0",
            keys,
            repo,
            tokens,
            outcomes,
            sink,
            selfPages,
            { page ->
                recoveryCalls += page
                recover(page)
            },
            { page ->
                serviceStops++
                stoppedPages += page
            },
            test.backgroundScope,
            { test.testScheduler.currentTime }
        )

        init {
            controller.start()
        }

        suspend fun sender(state: String = "delivered", withKey: Boolean = true) {
            repo.start(PAGE, PageRole.SENDER, SESSION, "user-2", "normal", state)
            if (withKey) {
                keys.put(PAGE, KEY.copyOf())
                keys.putPeers(PAGE, setOf(PEER_DEVICE, STRANGER))
                keys.putSenderKey(PAGE, SENDER_KEY)
            }
        }

        suspend fun receiver(withKey: Boolean = true) {
            repo.start(PAGE, PageRole.RECEIVER, SESSION, "user-2", "normal", "answered")
            if (withKey) {
                keys.put(PAGE, KEY.copyOf())
                keys.putPeers(PAGE, setOf(PEER_DEVICE))
            }
        }

        fun accepted(kind: OutboxKind) {
            outcomes.tryEmit(OutboxOutcome("row", kind, PAGE, OutboxOutcome.Result.Sent(null)))
        }

        fun ws(index: Int = 0): FakeWs = transport.sockets[index]

        /** Opens the page, connects the first socket and completes hello; leaves `join` sent. */
        fun connect(index: Int = 0, protocol: Int = 1): FakeWs {
            test.testScheduler.runCurrent()
            val ws = ws(index)
            ws.serverOpen()
            ws.serverText(serverHello(protocol = protocol))
            test.testScheduler.runCurrent()
            return ws
        }

        /** The join acknowledgement and the peer's arrival. */
        fun joined(ws: FakeWs, state: String = "delivered", peer: Boolean = true) {
            ws.serverText("""{"type":"page_state","page_id":"$PAGE","state":"$state"}""")
            if (peer) ws.serverText("""{"type":"peer_joined","device_id":"$PEER_DEVICE"}""")
            test.testScheduler.runCurrent()
        }
    }

    private fun FakeWs.requestId(): String =
        Regex("\"transmission_id\":\"([0-9a-f-]+)\"").find(lastOf("floor_request"))!!.groupValues[1]

    private fun FakeWs.grant(talker: String, id: String) =
        serverText("""{"type":"floor_granted","talker":"$talker","transmission_id":"$id"}""")

    // ---- joining ----

    @Test(timeout = 60_000)
    fun theSenderJoinsOnlyAfterTheCreateWasAccepted() = runTest {
        val rig = Rig(this)
        rig.sender(state = "sending")
        rig.controller.open(PAGE)
        runCurrent()
        advanceTimeBy(60_000)
        assertTrue("no socket before the 2xx", transport.sockets.isEmpty())
        rig.accepted(OutboxKind.PAGE)
        val ws = rig.connect()
        // The ping right behind the join marks the end of its snapshot (its pong comes after it).
        assertEquals(listOf("hello", "join", "ping"), ws.types().take(3))
        assertTrue(ws.lastOf("join").contains(SESSION))
    }

    @Test(timeout = 60_000)
    fun aSenderRowPastSendingCountsAsAccepted() = runTest {
        val rig = Rig(this)
        rig.sender(state = "delivered")
        rig.controller.open(PAGE)
        assertTrue(rig.connect().types().contains("join"))
    }

    @Test(timeout = 60_000)
    fun theAnswererJoinsOnlyAfterTheAnswerWasAccepted() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.controller.open(PAGE)
        runCurrent()
        assertTrue(transport.sockets.isEmpty())
        rig.accepted(OutboxKind.ANSWER)
        assertTrue(rig.connect().types().contains("join"))
    }

    @Test(timeout = 60_000)
    fun anAcceptanceSeenBeforeTheServiceStartedStillCounts() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        runCurrent()
        rig.controller.open(PAGE)
        assertTrue(rig.connect().types().contains("join"))
    }

    @Test(timeout = 60_000)
    fun aSelfPageNeverOpensASession() = runTest {
        val rig = Rig(this)
        rig.sender()
        selfPages.add(PAGE)
        rig.controller.open(PAGE)
        runCurrent()
        assertTrue(transport.sockets.isEmpty())
        assertEquals(1, serviceStops)
        assertFalse(rig.controller.eligible(PAGE))
    }

    @Test(timeout = 60_000)
    fun aMissingKeyMakesLiveTalkUnavailableButKeepsThePage() = runTest {
        val rig = Rig(this)
        rig.sender(withKey = false)
        val events = mutableListOf<TalkEvent>()
        backgroundScope.launch { rig.controller.events.collect { events += it } }
        runCurrent()
        rig.controller.open(PAGE)
        runCurrent()
        assertTrue(transport.sockets.isEmpty())
        assertTrue("and not ended locally", sink.ended.isEmpty())
        assertEquals(TalkConnection.UNAVAILABLE, rig.controller.state.value.connection)
        assertEquals(SessionStage.WAITING, rig.controller.state.value.stage)
        assertTrue(events.contains(TalkEvent.TalkUnavailable))
        assertEquals(listOf(PAGE), stoppedPages)
        assertEquals("the row is still there", PAGE, rig.repo.current()?.pageId)
        // A later open for the same page is refused and the service is stopped again.
        rig.controller.open(PAGE)
        runCurrent()
        assertTrue(transport.sockets.isEmpty())
        assertEquals(listOf(PAGE, PAGE), stoppedPages)
    }

    // ---- the sender key (protocol 2) ----

    private fun FakeWs.sent() = types().filterNot { it == "ping" }

    private val senderKeyText = TalkCodec.encode(ClientMessage.SenderKey(SENDER_KEY))

    @Test(timeout = 60_000)
    fun aProtocolTwoSenderSendsItsKeyAfterEveryJoin() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect(protocol = 2)
        assertEquals("not before the join is acknowledged", listOf("hello", "join"), first.sent())
        rig.joined(first)
        assertEquals(listOf("hello", "join", "sender_key"), first.sent())
        assertEquals(senderKeyText, first.texts.last())
        first.serverText("""{"type":"page_state","page_id":"$PAGE","state":"answered"}""")
        runCurrent()
        assertEquals("once per join", 1, first.types().count { it == "sender_key" })
        first.serverClose(4503)
        runCurrent()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1, protocol = 2)
        rig.joined(second)
        assertEquals(listOf("hello", "join", "sender_key"), second.sent())
        assertEquals(senderKeyText, second.texts.last())
    }

    @Test(timeout = 60_000)
    fun aRefusedJoinSendsNoKeyUntilTheRetrySucceeds() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = 2)
        ws.serverText("""{"type":"error","code":"not_found"}""")
        runCurrent()
        assertFalse(ws.types().contains("sender_key"))
        advanceTimeBy(TalkSessionController.JOIN_RETRY_MS)
        rig.joined(ws)
        assertEquals(1, ws.types().count { it == "sender_key" })
    }

    @Test(timeout = 60_000)
    fun theAnswererNeverSendsASenderKey() = runTest {
        val rig = Rig(this)
        rig.receiver()
        keys.putSenderKey(PAGE, SENDER_KEY)
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = 2)
        rig.joined(ws, state = "answered")
        assertFalse(ws.types().contains("sender_key"))
    }

    @Test(timeout = 60_000)
    fun aProtocolOneSocketGetsNoSenderKeyThoughHelloAdvertisesTwo() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = 1)
        rig.joined(ws)
        assertEquals(listOf("hello", "join"), ws.sent())
        assertTrue(ws.texts.first().contains("\"protocol\":2"))
    }

    @Test(timeout = 60_000)
    fun aSocketThatReconnectsOnProtocolOneStopsSendingTheKey() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect(protocol = 2)
        rig.joined(first)
        first.serverClose(4503)
        runCurrent()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1, protocol = 1)
        rig.joined(second)
        assertFalse(second.types().contains("sender_key"))
    }

    @Test(timeout = 60_000)
    fun aSenderWithoutAKeyObjectSendsNothingMore() = runTest {
        val rig = Rig(this)
        rig.sender()
        keys.clear(PAGE)
        keys.put(PAGE, KEY.copyOf())
        keys.putPeers(PAGE, setOf(PEER_DEVICE))
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = 2)
        rig.joined(ws)
        assertFalse(ws.types().contains("sender_key"))
    }

    // ---- recovering the key after the process died ----

    private fun recovering(sessionId: String = SESSION, peers: Set<String> = setOf(PEER_DEVICE)) {
        recover = { page ->
            keys.put(page, KEY.copyOf())
            keys.putPeers(page, peers)
            keys.putSenderKey(page, SENDER_KEY)
            sessionId
        }
    }

    @Test(timeout = 60_000)
    fun aSenderWithoutTheKeyRecoversItAndJoinsWithTheEnvelopesSessionId() = runTest {
        val rig = Rig(this)
        rig.sender(withKey = false)
        recovering(sessionId = STRANGER)
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = 2)
        assertEquals(listOf(PAGE), recoveryCalls)
        assertTrue(ws.lastOf("join").contains(STRANGER))
        assertTrue(keys.has(PAGE))
        rig.joined(ws)
        assertEquals(senderKeyText, ws.texts.last())
        assertEquals(TalkConnection.LIVE, rig.controller.state.value.connection)
        assertEquals(1, rig.controller.audioSessionsBuilt)
    }

    @Test(timeout = 60_000)
    fun endingThePageWhileTheRecoveryIsInFlightLeavesNoKeyBehind() = runTest {
        val rig = Rig(this)
        rig.sender(withKey = false)
        val release = CompletableDeferred<Unit>()
        recover = { page ->
            release.await()
            keys.put(page, KEY.copyOf())
            keys.putPeers(page, setOf(PEER_DEVICE))
            keys.putSenderKey(page, SENDER_KEY)
            SESSION
        }
        rig.controller.open(PAGE)
        runCurrent()
        keys.clear(PAGE)
        rig.repo.end(PAGE)
        release.complete(Unit)
        runCurrent()
        assertFalse(keys.has(PAGE))
        assertNull(keys.senderKey(PAGE))
        assertTrue(keys.peers(PAGE).isEmpty())
    }

    @Test(timeout = 60_000)
    fun aSenderThatStillHoldsTheKeyAsksNobody() = runTest {
        val rig = Rig(this)
        rig.sender()
        recovering()
        rig.controller.open(PAGE)
        rig.connect()
        assertTrue(recoveryCalls.isEmpty())
    }

    @Test(timeout = 60_000)
    fun theAnswererNeverRecovers() = runTest {
        val rig = Rig(this)
        rig.receiver(withKey = false)
        recovering()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        runCurrent()
        assertTrue(recoveryCalls.isEmpty())
        assertEquals(TalkConnection.UNAVAILABLE, rig.controller.state.value.connection)
    }

    @Test(timeout = 60_000)
    fun aFailedRecoveryLeavesNoKeyNoSocketAndTheRowToEnd() = runTest {
        val rig = Rig(this)
        rig.sender(withKey = false)
        rig.controller.open(PAGE)
        runCurrent()
        advanceTimeBy(10 * 60_000)
        assertEquals("one attempt, no retry loop", listOf(PAGE), recoveryCalls)
        assertTrue(transport.sockets.isEmpty())
        assertFalse(keys.has(PAGE))
        assertEquals(TalkConnection.UNAVAILABLE, rig.controller.state.value.connection)
        assertEquals("the page can still be ended", PAGE, rig.repo.current()?.pageId)
        assertFalse(rig.controller.requestEnd(PAGE))
        rig.controller.open(PAGE)
        runCurrent()
        assertEquals("a reopen does not try again", listOf(PAGE), recoveryCalls)
    }

    @Test(timeout = 60_000)
    fun aRecoveryWithNoPeerToTalkToMakesTalkUnavailable() = runTest {
        val rig = Rig(this)
        rig.sender(withKey = false)
        recovering(peers = emptySet())
        rig.controller.open(PAGE)
        runCurrent()
        assertTrue(transport.sockets.isEmpty())
        assertEquals(TalkConnection.UNAVAILABLE, rig.controller.state.value.connection)
    }

    @Test(timeout = 60_000)
    fun aSelfPageNeverRecovers() = runTest {
        val rig = Rig(this)
        rig.sender(withKey = false)
        selfPages.add(PAGE)
        recovering()
        rig.controller.open(PAGE)
        runCurrent()
        assertTrue(recoveryCalls.isEmpty())
        assertTrue(transport.sockets.isEmpty())
    }

    @Test(timeout = 60_000)
    fun aSessionEndedBeforeAcceptanceStopsTheService() = runTest {
        for (variant in listOf("receiver-row", "receiver-rejected", "sender-row", "sender-expired")) {
            serviceStops = 0
            val rig = Rig(this)
            val receiver = variant.startsWith("receiver")
            if (receiver) rig.receiver() else rig.sender(state = "sending")
            rig.controller.open(PAGE)
            runCurrent()
            assertTrue(variant, transport.sockets.isEmpty())
            assertEquals(variant, 0, serviceStops)
            if (variant.endsWith("row")) {
                rig.repo.end(PAGE)
            } else {
                val kind = if (receiver) OutboxKind.ANSWER else OutboxKind.PAGE
                val result = if (receiver) {
                    OutboxOutcome.Result.Rejected(410, "page_ended")
                } else {
                    OutboxOutcome.Result.Expired
                }
                outcomes.tryEmit(OutboxOutcome("row", kind, PAGE, result))
            }
            runCurrent()
            assertEquals(variant, 1, serviceStops)
            assertTrue(variant, transport.sockets.isEmpty())
            assertEquals(variant, SessionStage.ENDED, rig.controller.state.value.stage)
        }
    }

    @Test(timeout = 60_000)
    fun theRoleSelectsTheAudioSession() = runTest {
        val rig = Rig(this)
        // Answerer: the peer must be the envelope's from_device; a stranger's peer_joined is refused.
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws, "answered", peer = false)
        ws.serverText("""{"type":"peer_joined","device_id":"$STRANGER"}""")
        runCurrent()
        assertFalse(rig.controller.state.value.peerPresent)
        ws.serverText("""{"type":"peer_joined","device_id":"$PEER_DEVICE"}""")
        runCurrent()
        assertTrue(rig.controller.state.value.peerPresent)
    }

    @Test(timeout = 60_000)
    fun aRefusedJoinIsRetriedOnTheSameSocketThenThePageIsOver() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        val notFound = """{"type":"error","code":"not_found"}"""
        ws.serverText(notFound)
        runCurrent()
        advanceTimeBy(TalkSessionController.JOIN_RETRY_MS + 1)
        assertEquals(2, ws.types().count { it == "join" })
        ws.serverText(notFound)
        runCurrent()
        advanceTimeBy(TalkSessionController.JOIN_RETRY_MS + 1)
        assertEquals(3, ws.types().count { it == "join" })
        assertEquals(1, transport.sockets.size)
        ws.serverText(notFound)
        runCurrent()
        assertEquals(listOf(PageRole.SENDER to SessionEndReason.ENDED_BY_PEER), sink.ended)
        assertEquals(1, serviceStops)
        assertFalse(keys.has(PAGE))
    }

    // ---- reconnecting and close codes ----

    @Test(timeout = 60_000)
    fun aDroppedSocketReconnectsWithTheSameAudioSession() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect()
        rig.joined(first)
        // A request is outstanding when the socket dies.
        assertTrue(rig.controller.pressTalk())
        val stale = first.requestId()
        first.serverClose(4503)
        runCurrent()
        assertEquals(TalkConnection.CONNECTING, rig.controller.state.value.connection)
        assertFalse(rig.controller.state.value.peerPresent)
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1)
        assertTrue(second.types().contains("join"))
        rig.joined(second)
        assertEquals(1, rig.controller.audioSessionsBuilt)
        // The old request's grant is not honoured as a transmission: onSocketClosed ran before the reconnect.
        second.grant(OWN, stale)
        runCurrent()
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)
        assertTrue(second.lastOf("floor_release").contains(stale))
    }

    @Test(timeout = 60_000)
    fun aSocketLostWhileTalkingStopsCapture() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        assertTrue(rig.controller.pressTalk())
        ws.grant(OWN, ws.requestId())
        runCurrent()
        assertEquals(TalkFloor.YOU, rig.controller.state.value.floor)
        val events = mutableListOf<TalkEvent>()
        backgroundScope.launch { rig.controller.events.collect { events += it } }
        runCurrent()
        ws.serverClose(1006)
        runCurrent()
        assertTrue(events.contains(TalkEvent.StopCapture))
        assertTrue(events.contains(TalkEvent.FloorReleased(yours = true)))
        assertFalse(rig.controller.sendOpus(ByteArray(20)))
    }

    @Test(timeout = 60_000)
    fun replacedSocketIsNeverReconnected() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        ws.serverClose(4409)
        runCurrent()
        advanceTimeBy(10 * 60_000)
        assertEquals(1, transport.sockets.size)
        assertEquals(TalkConnection.UNAVAILABLE, rig.controller.state.value.connection)
        assertEquals("the page goes on", SessionStage.WAITING, rig.controller.state.value.stage)
        assertEquals(1, serviceStops)
        rig.controller.open(PAGE)
        runCurrent()
        assertEquals("a replaced page is never reopened", 1, transport.sockets.size)
    }

    @Test(timeout = 60_000)
    fun unauthorizedClosesAreTreatedLikeA401() = runTest {
        for (code in listOf(4401, 4403)) {
            auth.rejected.clear()
            transport.sockets.clear()
            val rig = Rig(this)
            rig.sender()
            rig.controller.open(PAGE)
            val ws = rig.connect()
            ws.serverClose(code)
            runCurrent()
            advanceTimeBy(10 * 60_000)
            assertEquals("code $code", 1, transport.sockets.size)
            assertEquals(listOf(WS_TOKEN), auth.rejected)
            assertEquals(CloseReaction.UNAUTHORIZED, CloseReaction.of(code, null))
            rig.controller.stop(PAGE)
            runCurrent()
        }
    }

    @Test(timeout = 60_000)
    fun http404OnUpgradeMeansTalkUnavailableAndNoReconnect() = runTest {
        val rig = Rig(this)
        rig.sender()
        val events = mutableListOf<TalkEvent>()
        backgroundScope.launch { rig.controller.events.collect { events += it } }
        rig.controller.open(PAGE)
        runCurrent()
        rig.ws().failUpgrade(404)
        runCurrent()
        advanceTimeBy(10 * 60_000)
        assertEquals(1, transport.sockets.size)
        assertEquals(TalkConnection.UNAVAILABLE, rig.controller.state.value.connection)
        assertTrue(events.contains(TalkEvent.TalkUnavailable))
        assertEquals(1, serviceStops)
        assertTrue("the key stays: paging still works", keys.has(PAGE))
        assertEquals(SessionStage.WAITING, rig.controller.state.value.stage)
        rig.controller.open(PAGE)
        runCurrent()
        assertEquals("no retry for this page", 1, transport.sockets.size)
    }

    @Test(timeout = 60_000)
    fun networkFailureReconnectsWithGrowingJitteredBackoff() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        runCurrent()
        rig.ws().failUpgrade(null)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, transport.sockets.size)
        transport.sockets[1].failUpgrade(null)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("second delay is longer than the first", 2, transport.sockets.size)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, transport.sockets.size)
    }

    // ---- session end ----

    @Test(timeout = 60_000)
    fun sessionEndCleansUpEverything() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        val events = mutableListOf<TalkEvent>()
        backgroundScope.launch { rig.controller.events.collect { events += it } }
        runCurrent()
        ws.serverText("""{"type":"session_end","reason":"idle"}""")
        runCurrent()
        assertEquals(listOf(PageRole.SENDER to SessionEndReason.IDLE), sink.ended)
        assertFalse("key cleared", keys.has(PAGE))
        assertEquals(1, serviceStops)
        assertEquals(SessionStage.ENDED, rig.controller.state.value.stage)
        assertEquals(1000, ws.closedWith)
        assertTrue(events.contains(TalkEvent.SessionEnded(SessionEndReason.IDLE)))
        advanceTimeBy(10 * 60_000)
        assertEquals(1, transport.sockets.size)
        assertFalse("audio is closed: no transmit", rig.controller.pressTalk())
    }

    @Test(timeout = 60_000)
    fun theRowGoingAwayEndsTheSession() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        rig.repo.end(PAGE)
        runCurrent()
        assertEquals(1000, ws.closedWith)
        assertEquals(1, serviceStops)
        advanceTimeBy(10 * 60_000)
        assertEquals(1, transport.sockets.size)
    }

    @Test(timeout = 60_000)
    fun endIsSentOverTheSocketWhenJoinedAndTheControllerNeverCancelsByRestItself() = runTest {
        val rig = Rig(this)
        rig.sender()
        assertFalse("no session yet", rig.controller.requestEnd(PAGE))
        rig.controller.open(PAGE)
        val ws = rig.connect()
        assertFalse("not joined yet", rig.controller.requestEnd(PAGE))
        rig.joined(ws)
        assertTrue(rig.controller.requestEnd(PAGE))
        assertTrue(ws.types().contains("session_end"))
        // The caller ends the row right away and the server never confirms: the REST cancel is the caller's.
        rig.repo.end(PAGE)
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(1, serviceStops)
        assertEquals("the queued session_end is flushed before the close", 1000, ws.closedWith)
    }

    @Test(timeout = 60_000)
    fun aConfirmedEndCleansUpWithoutAnyCancelFromTheController() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        assertTrue(rig.controller.requestEnd(PAGE))
        ws.serverText("""{"type":"session_end","reason":"ended_by_you"}""")
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(listOf(PageRole.SENDER to SessionEndReason.ENDED_BY_YOU), sink.ended)
    }

    // ---- progress ----

    @Test(timeout = 60_000)
    fun pageStateIsForwardedAndTheStateNeverRegresses() = runTest {
        val rig = Rig(this)
        rig.sender(state = "sending")
        rig.accepted(OutboxKind.PAGE)
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws, "acknowledged", peer = false)
        ws.serverText("""{"type":"page_state","page_id":"$PAGE","state":"delivered"}""")
        runCurrent()
        assertEquals(WsPageState.ACKNOWLEDGED, rig.controller.state.value.pageProgress)
        assertEquals(listOf(WsPageState.ACKNOWLEDGED, WsPageState.DELIVERED), sink.states)
        assertEquals(SessionStage.WAITING, rig.controller.state.value.stage)
        ws.serverText("""{"type":"page_state","page_id":"$PAGE","state":"answered"}""")
        runCurrent()
        assertEquals(SessionStage.CONVERSATION, rig.controller.state.value.stage)
    }

    @Test(timeout = 60_000)
    fun progressFollowsThePageOrderNotTheEnumOrder() = runTest {
        val rig = Rig(this)
        rig.sender(state = "sending")
        rig.accepted(OutboxKind.PAGE)
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws, "not_delivered", peer = false)
        assertEquals(WsPageState.NOT_DELIVERED, rig.controller.state.value.pageProgress)
        for ((wire, expected) in listOf(
            "delivered" to WsPageState.DELIVERED,
            "acknowledged" to WsPageState.ACKNOWLEDGED,
            "not_delivered" to WsPageState.ACKNOWLEDGED,
            "delivered" to WsPageState.ACKNOWLEDGED
        )) {
            ws.serverText("""{"type":"page_state","page_id":"$PAGE","state":"$wire"}""")
            runCurrent()
            assertEquals(wire, expected, rig.controller.state.value.pageProgress)
        }
    }

    @Test(timeout = 60_000)
    fun theAnsweredTransitionAlsoWorksWhenFcmMovesTheRow() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        rig.connect()
        assertEquals(SessionStage.WAITING, rig.controller.state.value.stage)
        rig.repo.updateState(PAGE, "answered")
        runCurrent()
        assertEquals(SessionStage.CONVERSATION, rig.controller.state.value.stage)
    }

    // ---- floor ----

    @Test(timeout = 60_000)
    fun requestGrantTransmitAndRelease() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        assertFalse("nothing granted yet", rig.controller.sendOpus(ByteArray(20)))
        assertTrue(rig.controller.pressTalk())
        assertFalse("already pressed", rig.controller.pressTalk())
        val id = ws.requestId()
        ws.grant(OWN, id)
        runCurrent()
        assertEquals(TalkFloor.YOU, rig.controller.state.value.floor)
        assertTrue(rig.controller.sendOpus(ByteArray(20) { 7 }))
        assertEquals(1, ws.binaries.size)
        assertEquals(21 + 20 + 16, ws.binaries[0].size)
        rig.controller.releaseTalk()
        assertTrue(ws.lastOf("floor_release").contains(id))
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)
        assertFalse(rig.controller.sendOpus(ByteArray(20)))
        assertEquals(1, ws.binaries.size)
        ws.serverText("""{"type":"floor_released","talker":"$OWN","transmission_id":"$id","reason":"released"}""")
        runCurrent()
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)
    }

    @Test(timeout = 60_000)
    fun busyBuzzesAndTheNextRequestUsesANewId() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        val events = mutableListOf<TalkEvent>()
        backgroundScope.launch { rig.controller.events.collect { events += it } }
        runCurrent()
        assertTrue(rig.controller.pressTalk())
        val first = ws.requestId()
        ws.serverText("""{"type":"floor_busy"}""")
        runCurrent()
        assertTrue(events.contains(TalkEvent.FloorBusy))
        assertEquals(TalkFloor.BUSY, rig.controller.state.value.floor)
        rig.controller.releaseTalk()
        assertTrue(rig.controller.pressTalk())
        assertNotEquals(first, ws.requestId())
        // The refused request's late grant (should never happen) is answered with a release, not audio.
        ws.grant(OWN, first)
        runCurrent()
        assertTrue(ws.lastOf("floor_release").contains(first))
        assertFalse(rig.controller.sendOpus(ByteArray(20)))
    }

    @Test(timeout = 60_000)
    fun releasingBeforeTheGrantIsHonouredWithAFloorRelease() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        assertTrue(rig.controller.pressTalk())
        val id = ws.requestId()
        rig.controller.releaseTalk()
        assertFalse(ws.types().contains("floor_release"))
        ws.grant(OWN, id)
        runCurrent()
        assertTrue(ws.lastOf("floor_release").contains(id))
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)
        assertFalse(rig.controller.sendOpus(ByteArray(20)))
    }

    @Test(timeout = 60_000)
    fun aGrantToUsWithoutARequestIsReleasedNotUsed() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        ws.grant(OWN, "99999999-9999-4999-8999-999999999999")
        runCurrent()
        assertTrue(ws.lastOf("floor_release").contains("99999999-9999-4999-8999-999999999999"))
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)
    }

    @Test(timeout = 60_000)
    fun noPeerFailsTheRequest() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws, peer = false)
        val events = mutableListOf<TalkEvent>()
        backgroundScope.launch { rig.controller.events.collect { events += it } }
        runCurrent()
        assertTrue(rig.controller.pressTalk())
        ws.serverText("""{"type":"error","code":"no_peer"}""")
        runCurrent()
        assertTrue(events.contains(TalkEvent.NoPeer))
        rig.controller.releaseTalk()
        assertTrue(rig.controller.pressTalk())
    }

    @Test(timeout = 60_000)
    fun pressingBeforeTheJoinWasAcknowledgedDoesNothing() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        assertFalse(rig.controller.pressTalk())
        assertFalse(ws.types().contains("floor_request"))
    }

    // ---- frames ----

    @Test(timeout = 60_000)
    fun onlyAcceptedFramesReachThePlayerAndOnlyForAValidatedTalker() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        val got = mutableListOf<IncomingOpus>()
        backgroundScope.launch { rig.controller.incoming.collect { if (it is IncomingOpus) got += it } }
        runCurrent()

        val peer = AudioSession.forAnswerer(KEY.copyOf(), SESSION, PEER_DEVICE, OWN)
        val id = peer.requestFloor()
        val sealer = (peer.onFloorGranted(PEER_DEVICE, id) as GrantOutcome.Transmit).sealer
        val frame0 = sealer.seal(ByteArray(20) { 1 })
        val frame1 = sealer.seal(ByteArray(20) { 2 })

        // Before any grant: dropped silently.
        ws.serverBinary(frame0)
        runCurrent()
        assertTrue(got.isEmpty())

        // A grant naming a stranger is ignored; the floor state does not change.
        ws.grant(STRANGER, id)
        runCurrent()
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)

        ws.grant(PEER_DEVICE, id)
        runCurrent()
        assertEquals(TalkFloor.PEER_TALKING, rig.controller.state.value.floor)
        ws.serverBinary(frame0)
        ws.serverBinary(frame0) // replay
        ws.serverBinary(ByteArray(40) { 9 }) // garbage
        ws.serverBinary(frame1.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) // tampered
        runCurrent()
        assertEquals(1, got.size)
        assertArrayEquals(ByteArray(20) { 1 }, got[0].opus)
        assertEquals(id, got[0].transmissionId)
        assertEquals(0, got[0].seq)

        ws.serverText(
            """{"type":"floor_released","talker":"$PEER_DEVICE","transmission_id":"$id","reason":"silence"}"""
        )
        runCurrent()
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)
        ws.serverBinary(frame0)
        runCurrent()
        assertEquals("nothing after the release", 1, got.size)
    }

    // ---- protocol 2: clips and heard (section 13) ----

    private class PeerTalker {
        private val phone = AudioSession.forAnswerer(KEY.copyOf(), SESSION, PEER_DEVICE, OWN)
        val id = phone.requestFloor()
        private val sealer = (phone.onFloorGranted(PEER_DEVICE, id) as GrantOutcome.Transmit).sealer
        fun frame(): ByteArray = sealer.seal(ByteArray(20) { 1 })
    }

    private fun FakeWs.clip(id: String, last: Int, talker: String = PEER_DEVICE) =
        serverText("""{"type":"clip","talker":"$talker","transmission_id":"$id","last_seq":$last}""")

    private fun FakeWs.heards(): List<String> = texts.filter { "\"type\":\"heard\"" in it }

    @Test(timeout = 60_000)
    fun onProtocolTwoAnAcceptedClipReachesThePlayerAndItsLastFrameIsHeard() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.controller.open(PAGE)
        rig.accepted(OutboxKind.ANSWER)
        val ws = rig.connect(protocol = 2)
        rig.joined(ws)
        val got = mutableListOf<Incoming>()
        backgroundScope.launch { rig.controller.incoming.collect { got += it } }
        runCurrent()
        val pingsAtJoin = ws.types().count { it == "ping" }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("pings every 2 s once joined on protocol 2", pingsAtJoin + 1, ws.types().count { it == "ping" })

        val t = PeerTalker()
        ws.clip(t.id, 1)
        ws.serverBinary(t.frame())
        runCurrent()
        assertTrue(ws.heards().isEmpty())
        ws.serverBinary(t.frame())
        runCurrent()
        assertEquals(listOf("""{"type":"heard","transmission_id":"${t.id}","last_seq":1}"""), ws.heards())
        val clip = got[0] as ClipStarted
        assertEquals(t.id, clip.transmissionId)
        assertEquals(1, clip.lastSeq)
        assertEquals(listOf(0, 1), got.drop(1).map { (it as IncomingOpus).seq })
        // a clip naming another talker is refused: its frames never reach the player
        val other = PeerTalker()
        ws.clip(other.id, 0, talker = STRANGER)
        ws.serverBinary(other.frame())
        runCurrent()
        assertEquals(3, got.size)
    }

    @Test(timeout = 60_000)
    fun onProtocolTwoAClipEndingThePeersTransmissionFreesTheFloorAndReportsWhatWasHeard() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.controller.open(PAGE)
        rig.accepted(OutboxKind.ANSWER)
        val ws = rig.connect(protocol = 2)
        rig.joined(ws)
        val events = mutableListOf<TalkEvent>()
        val got = mutableListOf<Incoming>()
        backgroundScope.launch { rig.controller.events.collect { events += it } }
        backgroundScope.launch { rig.controller.incoming.collect { got += it } }
        runCurrent()
        val t = PeerTalker()
        ws.grant(PEER_DEVICE, t.id)
        ws.serverBinary(t.frame())
        runCurrent()
        assertEquals(TalkFloor.PEER_TALKING, rig.controller.state.value.floor)
        ws.clip(t.id, 1)
        runCurrent()
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)
        assertTrue(events.contains(TalkEvent.FloorReleased(yours = false)))
        assertEquals(listOf("""{"type":"heard","transmission_id":"${t.id}","last_seq":0}"""), ws.heards())
        assertTrue(got.contains(LiveEnded(t.id)))
        assertTrue(got.last() is ClipStarted)
    }

    @Test(timeout = 60_000)
    fun onProtocolTwoAClipReplayFasterThanThePlayerCollectsLosesNothing() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.controller.open(PAGE)
        rig.accepted(OutboxKind.ANSWER)
        val ws = rig.connect(protocol = 2)
        rig.joined(ws)
        val got = mutableListOf<Incoming>()
        backgroundScope.launch { rig.controller.incoming.collect { got += it } }
        runCurrent()
        val first = PeerTalker()
        val second = PeerTalker()
        ws.clip(first.id, 1199)
        repeat(1200) { ws.serverBinary(first.frame()) }
        ws.clip(second.id, 1)
        repeat(2) { ws.serverBinary(second.frame()) }
        runCurrent()
        val expected = listOf("clip ${first.id}") + (0..1199).map { "${first.id} $it" } +
            "clip ${second.id}" + (0..1).map { "${second.id} $it" }
        val seen = got.map {
            when (it) {
                is ClipStarted -> "clip ${it.transmissionId}"
                is IncomingOpus -> "${it.transmissionId} ${it.seq}"
                is LiveEnded -> "ended ${it.transmissionId}"
            }
        }
        assertEquals(expected, seen)
    }

    /** As the answerer: a grant, a frame and its release, then a clip of a later transmission with its frame. */
    private suspend fun TestScope.releaseThenClip(protocol: Int): Pair<FakeWs, List<String>> {
        val rig = Rig(this)
        rig.receiver()
        rig.controller.open(PAGE)
        rig.accepted(OutboxKind.ANSWER)
        val ws = rig.connect(protocol = protocol)
        rig.joined(ws)
        val t = PeerTalker()
        ws.grant(PEER_DEVICE, t.id)
        ws.serverBinary(t.frame())
        ws.serverText(
            """{"type":"floor_released","talker":"$PEER_DEVICE","transmission_id":"${t.id}","reason":"released"}"""
        )
        val later = PeerTalker()
        ws.clip(later.id, 0)
        ws.serverBinary(later.frame())
        runCurrent()
        return ws to listOf(t.id, later.id)
    }

    @Test(timeout = 60_000)
    fun onProtocolTwoAReleaseAndACompletedClipAreHeard() = runTest {
        val (ws, ids) = releaseThenClip(protocol = 2)
        assertEquals(
            ids.map { """{"type":"heard","transmission_id":"$it","last_seq":0}""" },
            ws.heards()
        )
    }

    @Test(timeout = 60_000)
    fun onProtocolOneThereIsNoHeardAndAClipIsIgnored() = runTest {
        val (ws, _) = releaseThenClip(protocol = 1)
        assertTrue(ws.heards().isEmpty())
    }

    @Test(timeout = 60_000)
    fun aClipFromThePeerSatisfiesTheSendersFirstTalkerRule() = runTest {
        val rig = Rig(this)
        rig.sender(state = "answered")
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = 2)
        rig.joined(ws, state = "answered", peer = false)
        assertTrue(rig.controller.state.value.firstTalkerLocked)
        ws.clip(PeerTalker().id, 0)
        runCurrent()
        assertFalse(rig.controller.state.value.firstTalkerLocked)
    }

    @Test(timeout = 60_000)
    fun aStrangerPeerJoinedIsNeverPinnedBySender() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws, peer = false)
        ws.serverText("""{"type":"peer_joined","device_id":"ffffffff-ffff-4fff-8fff-ffffffffffff"}""")
        runCurrent()
        assertFalse(rig.controller.state.value.peerPresent)
    }

    // ---- the first-talker rule ----

    private fun TalkSessionController.locked() = state.value.firstTalkerLocked

    /** A transmission id of the receiver's, granted to them on [ws]. */
    private fun TestScope.peerTalks(ws: FakeWs): String {
        val id = AudioSession.forAnswerer(KEY.copyOf(), SESSION, PEER_DEVICE, OWN).requestFloor()
        ws.grant(PEER_DEVICE, id)
        runCurrent()
        return id
    }

    private fun FakeWs.peerReleased(id: String) = serverText(
        """{"type":"floor_released","talker":"$PEER_DEVICE","transmission_id":"$id","reason":"released"}"""
    )

    /** The server's answer to the ping sent behind the join: the join snapshot is complete. */
    private fun TestScope.pong(ws: FakeWs) {
        ws.serverText("""{"type":"pong","t":$currentTime,"server_time":1}""")
        runCurrent()
    }

    @Test(timeout = 60_000)
    fun aPeerAlreadyThereAtJoinLocksTheSenderForTenSecondsOfSilence() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        assertTrue("locked from the start", rig.controller.locked())
        // The join snapshot names the peer: the window starts now, however late the screen looks.
        rig.joined(ws)
        advanceTimeBy(FirstTalkerRule.WINDOW_MS - 1)
        runCurrent()
        assertTrue(rig.controller.locked())
        advanceTimeBy(1)
        runCurrent()
        assertFalse(rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun withoutThePeerTheSenderStaysLocked() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        rig.joined(rig.connect(), peer = false)
        advanceTimeBy(10 * FirstTalkerRule.WINDOW_MS)
        runCurrent()
        assertTrue(rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun aPeerTalkingInsideTheWindowKeepsTheLockUntilTheirRelease() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        val id = peerTalks(ws)
        advanceTimeBy(3 * FirstTalkerRule.WINDOW_MS)
        runCurrent()
        assertTrue(rig.controller.locked())
        ws.peerReleased(id)
        runCurrent()
        assertFalse(rig.controller.locked())
        // Once unlocked, the peer talking again changes nothing.
        peerTalks(ws)
        assertFalse(rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun aRejoinSnapshotWithoutTheGrantEndsThePeersTurnAtThePong() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect()
        rig.joined(first)
        peerTalks(first)
        first.serverClose(1006)
        runCurrent()
        assertTrue("suspended, not over: the rejoin may resume it", rig.controller.locked())
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1)
        rig.joined(second)
        assertTrue("the snapshot may still carry the grant", rig.controller.locked())
        assertEquals(listOf("hello", "join", "ping"), second.types().take(3))
        pong(second)
        assertFalse("no release will ever come for that transmission", rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun aRejoinSnapshotThatResumesThePeersFirstTransmissionKeepsTheLock() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect()
        rig.joined(first)
        val id = peerTalks(first)
        advanceTimeBy(4_000)
        first.serverClose(1006)
        runCurrent()
        assertTrue(rig.controller.locked())
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1)
        rig.joined(second)
        second.grant(PEER_DEVICE, id)
        pong(second)
        assertEquals(TalkFloor.PEER_TALKING, rig.controller.state.value.floor)
        assertTrue("the receiver is still speaking first", rig.controller.locked())
        advanceTimeBy(2 * FirstTalkerRule.WINDOW_MS)
        pong(second)
        assertTrue(rig.controller.locked())
        second.peerReleased(id)
        runCurrent()
        assertFalse(rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun aRejoinSnapshotWithTheReceiversNextTransmissionUnlocks() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect()
        rig.joined(first)
        peerTalks(first)
        first.serverClose(1006)
        runCurrent()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1)
        rig.joined(second)
        peerTalks(second)
        assertEquals(TalkFloor.PEER_TALKING, rig.controller.state.value.floor)
        assertFalse("a new id means the first transmission finished", rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun aPongBeforeTheRejoinIsAcknowledgedDoesNotEndTheSuspension() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect()
        rig.joined(first)
        val id = peerTalks(first)
        first.serverClose(1006)
        runCurrent()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1)
        // A ping the socket sent before the join is answered ahead of the snapshot.
        pong(second)
        assertTrue(rig.controller.locked())
        rig.joined(second)
        second.grant(PEER_DEVICE, id)
        pong(second)
        assertTrue(rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun aJoinPongTooLateToBeAnRttSampleStillSettlesTheRejoin() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect(protocol = 2)
        rig.joined(first)
        peerTalks(first)
        first.serverClose(1006)
        runCurrent()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1, protocol = 2)
        rig.joined(second)
        val joinPing = Regex("\"t\":(\\d+)").find(second.lastOf("ping"))!!.groupValues[1]
        advanceTimeBy(RttSamples.TIMEOUT_MS + 1)
        runCurrent()
        assertTrue("the snapshot may still carry the grant", rig.controller.locked())
        second.serverText("""{"type":"pong","t":$joinPing,"server_time":1}""")
        runCurrent()
        assertFalse("the snapshot is complete", rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun theSessionEndingWhileSuspendedEndsTalk() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect()
        rig.joined(first)
        peerTalks(first)
        first.serverClose(1006)
        runCurrent()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1)
        rig.joined(second)
        second.serverText("""{"type":"session_end","reason":"idle"}""")
        runCurrent()
        assertEquals(SessionStage.ENDED, rig.controller.state.value.stage)
        assertFalse(rig.controller.pressTalk())
    }

    @Test(timeout = 60_000)
    fun theSessionEndingWhileThePeerTalksLiftsTheLock() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val ws = rig.connect()
        rig.joined(ws)
        peerTalks(ws)
        ws.serverText("""{"type":"session_end","reason":"ended_by_peer"}""")
        runCurrent()
        assertFalse(rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun aReplayedPeerJoinedAfterAReconnectDoesNotRestartTheWindow() = runTest {
        val rig = Rig(this)
        rig.sender()
        rig.controller.open(PAGE)
        val first = rig.connect()
        rig.joined(first)
        val joinedAt = currentTime
        advanceTimeBy(FirstTalkerRule.WINDOW_MS / 2)
        first.serverClose(1006)
        runCurrent()
        assertTrue(rig.controller.locked())
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS / 10)
        rig.joined(rig.connect(1))
        assertTrue(currentTime < joinedAt + FirstTalkerRule.WINDOW_MS)
        assertTrue(rig.controller.locked())
        advanceTimeBy(joinedAt + FirstTalkerRule.WINDOW_MS - currentTime)
        runCurrent()
        assertFalse("ten seconds from the first sight of the peer", rig.controller.locked())
    }

    @Test(timeout = 60_000)
    fun theReceiverIsNeverLocked() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val ws = rig.connect()
        assertFalse(rig.controller.locked())
        rig.joined(ws, state = "answered")
        assertFalse(rig.controller.locked())
        assertTrue(rig.controller.pressTalk())
    }

    @Test(timeout = 60_000)
    fun theReceiverIsNeverLockedAcrossAReconnect() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val first = rig.connect()
        rig.joined(first, state = "answered")
        first.grant(PEER_DEVICE, AudioSession.forSender(KEY.copyOf(), SESSION, PEER_DEVICE, setOf(OWN)).requestFloor())
        runCurrent()
        first.serverClose(1006)
        runCurrent()
        assertFalse(rig.controller.locked())
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1)
        rig.joined(second, state = "answered")
        pong(second)
        assertFalse(rig.controller.locked())
        assertTrue(rig.controller.pressTalk())
    }

    // ---- the talker on protocol 2 (section 13.6) ----

    private fun packet(n: Int) = ByteArray(20) { (n + it).toByte() }

    private fun seqOf(frame: ByteArray) = java.nio.ByteBuffer.wrap(frame, 17, 4).int

    private fun FakeWs.released(id: String, reason: String) =
        serverText("""{"type":"floor_released","talker":"$OWN","transmission_id":"$id","reason":"$reason"}""")

    private fun FakeWs.lastSent(): String = types().last { it != "ping" }

    private fun TestScope.collectEvents(rig: Rig): MutableList<TalkEvent> {
        val events = mutableListOf<TalkEvent>()
        backgroundScope.launch { rig.controller.events.collect { events += it } }
        runCurrent()
        return events
    }

    /** The answerer, joined on a protocol-2 socket, talking under a granted id after [frames] captured packets. */
    private suspend fun TestScope.talkingOnTwo(rig: Rig, frames: Int, peer: Boolean = true): Pair<FakeWs, String> {
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = 2)
        rig.joined(ws, state = "answered", peer = peer)
        assertTrue(rig.controller.pressTalk())
        val id = ws.requestId()
        ws.grant(OWN, id)
        runCurrent()
        repeat(frames) { assertTrue(rig.controller.sendOpus(packet(it))) }
        return ws to id
    }

    /** Drops [ws], reconnects on protocol 2 and joins; returns the new socket (the `resume` is sent). */
    private fun TestScope.reconnectOnTwo(rig: Rig, ws: FakeWs, index: Int = 1): FakeWs {
        ws.serverClose(1006)
        runCurrent()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val next = rig.connect(index, protocol = 2)
        rig.joined(next, state = "answered")
        return next
    }

    @Test(timeout = 60_000)
    fun onProtocolTwoTheRequestSaysLiveModeAndFramesGoOutAsCaptured() = runTest {
        val rig = Rig(this)
        val (ws, id) = talkingOnTwo(rig, frames = 3)
        assertEquals(
            """{"type":"floor_request","transmission_id":"$id","clip":false}""",
            ws.lastOf("floor_request")
        )
        assertEquals(listOf(0, 1, 2), ws.binaries.map(::seqOf))
        assertTrue(rig.controller.state.value.capturing)
        assertTrue(rig.controller.state.value.sending)
    }

    @Test(timeout = 60_000)
    fun aTalkerWhoseSocketDropsResumesAndResendsTheSameBytesExactlyOnce() = runTest {
        val rig = Rig(this)
        val (first, id) = talkingOnTwo(rig, frames = 8)
        assertEquals(8, first.binaries.size)
        first.serverClose(1006)
        runCurrent()
        assertTrue("capture goes on while the socket is down", rig.controller.state.value.capturing)
        assertEquals(OwnNotice.SENDING_AS_CLIP, rig.controller.state.value.ownNotice)
        repeat(2) { assertTrue(rig.controller.sendOpus(packet(8 + it))) }
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1, protocol = 2)
        rig.joined(second, state = "answered")
        assertEquals("""{"type":"resume","transmission_id":"$id"}""", second.lastOf("resume"))
        assertTrue("nothing of it before the reply", second.binaries.isEmpty())
        assertTrue(rig.controller.sendOpus(packet(10)))
        assertTrue(second.binaries.isEmpty())
        second.serverText("""{"type":"resume_from","last_seq":4}""")
        runCurrent()
        assertEquals((5..10).toList(), second.binaries.map(::seqOf))
        for (i in 5..7) assertArrayEquals("seq $i resent byte for byte", first.binaries[i], second.binaries[i - 5])
        assertTrue(rig.controller.sendOpus(packet(11)))
        rig.controller.releaseTalk()
        runCurrent()
        assertEquals((5..11).toList(), second.binaries.map(::seqOf))
        assertEquals("floor_release", second.lastSent())
        assertTrue(second.lastOf("floor_release").contains(id))
        assertFalse("everything handed", rig.controller.state.value.sending)
        val events = collectEvents(rig)
        second.released(id, "released")
        runCurrent()
        assertFalse("capture had ended already", events.contains(TalkEvent.FloorReleased(yours = true)))
        assertEquals(OwnNotice.NONE, rig.controller.state.value.ownNotice)
        assertEquals(1, rig.controller.audioSessionsBuilt)
    }

    @Test(timeout = 60_000)
    fun aTransmissionClosedWhileAwayStopsAtTheReleaseAfterItsResumeFrom() = runTest {
        val rig = Rig(this)
        val (first, id) = talkingOnTwo(rig, frames = 4)
        rig.controller.releaseTalk()
        runCurrent()
        assertTrue(first.lastOf("floor_release").contains(id))
        val second = reconnectOnTwo(rig, first)
        assertTrue(second.lastOf("resume").contains(id))
        second.serverText("""{"type":"resume_from","last_seq":3}""")
        second.released(id, "released")
        runCurrent()
        assertTrue(second.binaries.isEmpty())
        assertFalse(rig.controller.state.value.sending)
        // The slot is free: the next press asks for the floor with a new id.
        assertTrue(rig.controller.pressTalk())
        assertNotEquals(id, second.requestId())
    }

    @Test(timeout = 60_000)
    fun aBadTransmissionIdAfterTheResumeFreesTheBufferAndSaysNotSent() = runTest {
        val rig = Rig(this)
        val (first, id) = talkingOnTwo(rig, frames = 4)
        val events = collectEvents(rig)
        val second = reconnectOnTwo(rig, first)
        assertTrue(second.lastOf("resume").contains(id))
        second.serverText("""{"type":"error","code":"bad_transmission_id"}""")
        runCurrent()
        assertEquals(OwnNotice.NOT_SENT, rig.controller.state.value.ownNotice)
        assertFalse(rig.controller.state.value.sending)
        assertFalse(rig.controller.state.value.capturing)
        assertTrue(events.contains(TalkEvent.StopCapture))
        assertFalse("never sealed under it again", rig.controller.sendOpus(packet(9)))
        assertTrue(second.binaries.isEmpty())
        assertTrue("the next press clears the line", rig.controller.pressTalk())
        assertEquals(OwnNotice.NONE, rig.controller.state.value.ownNotice)
    }

    @Test(timeout = 60_000)
    fun aSilenceReleaseKeepsCapturingAndSendingAsAClip() = runTest {
        val rig = Rig(this)
        val (ws, id) = talkingOnTwo(rig, frames = 3)
        val events = collectEvents(rig)
        ws.released(id, "silence")
        runCurrent()
        val state = rig.controller.state.value
        assertEquals(TalkFloor.IDLE, state.floor)
        assertTrue(state.capturing)
        assertTrue("nothing plays meanwhile", state.sending)
        assertEquals(OwnNotice.SENDING_AS_CLIP, state.ownNotice)
        assertFalse(events.contains(TalkEvent.StopCapture))
        assertTrue(rig.controller.sendOpus(packet(3)))
        assertEquals(listOf(0, 1, 2, 3), ws.binaries.map(::seqOf))
        // The peer may talk now; this phone's frames still go out.
        ws.grant(PEER_DEVICE, "77777777-7777-4777-8777-777777777777")
        runCurrent()
        assertEquals(TalkFloor.PEER_TALKING, rig.controller.state.value.floor)
        assertTrue(rig.controller.sendOpus(packet(4)))
        rig.controller.releaseTalk()
        assertTrue(ws.lastOf("floor_release").contains(id))
        assertEquals(5, ws.binaries.size)
        assertFalse(rig.controller.state.value.sending)
        ws.released(id, "released")
        runCurrent()
        assertFalse("capture had ended already", events.contains(TalkEvent.FloorReleased(yours = true)))
        assertEquals("the peer's floor is untouched", TalkFloor.PEER_TALKING, rig.controller.state.value.floor)
    }

    @Test(timeout = 60_000)
    fun aPressWaitsUntilThePreviousTransmissionIsClosed() = runTest {
        val rig = Rig(this)
        val (ws, id) = talkingOnTwo(rig, frames = 2)
        rig.controller.releaseTalk()
        assertTrue(rig.controller.pressTalk())
        assertEquals("no second request yet", 1, ws.types().count { it == "floor_request" })
        ws.released(id, "released")
        runCurrent()
        assertEquals(2, ws.types().count { it == "floor_request" })
        assertNotEquals(id, ws.requestId())
    }

    @Test(timeout = 60_000)
    fun aQuickRepressKeepsItsPrewarmWhenTheOldReleaseArrives() = runTest {
        val rig = Rig(this)
        val (ws, id) = talkingOnTwo(rig, frames = 2)
        val events = collectEvents(rig)
        rig.controller.releaseTalk()
        assertTrue(rig.controller.pressTalk())
        ws.released(id, "released")
        runCurrent()
        assertFalse(events.contains(TalkEvent.StopCapture))
        assertFalse(events.contains(TalkEvent.FloorReleased(yours = true)))
        val next = ws.requestId()
        assertNotEquals(id, next)
        ws.grant(OWN, next)
        runCurrent()
        assertTrue(rig.controller.state.value.capturing)
        assertTrue(rig.controller.sendOpus(packet(0)))
    }

    @Test(timeout = 60_000)
    fun aRequestGrantedWhileAwayIsReleasedAfterTheResumeFromMinusOne() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val first = rig.connect(protocol = 2)
        rig.joined(first, state = "answered")
        assertTrue(rig.controller.pressTalk())
        val id = first.requestId()
        val second = reconnectOnTwo(rig, first)
        assertTrue(second.lastOf("resume").contains(id))
        second.serverText("""{"type":"resume_from","last_seq":-1}""")
        runCurrent()
        assertTrue(second.lastOf("floor_release").contains(id))
        assertTrue(second.binaries.isEmpty())
        assertFalse(rig.controller.state.value.capturing)
        assertFalse(rig.controller.state.value.sending)
        assertTrue(rig.controller.pressTalk())
        assertNotEquals(id, second.requestId())
    }

    @Test(timeout = 60_000)
    fun aReleaseWhileStillPressingStopsCaptureAndTheNextPressAsksAgain() = runTest {
        val rig = Rig(this)
        val (ws, id) = talkingOnTwo(rig, frames = 2)
        val events = collectEvents(rig)
        ws.released(id, "released")
        runCurrent()
        assertTrue(events.contains(TalkEvent.StopCapture))
        assertFalse(rig.controller.state.value.capturing)
        assertFalse(rig.controller.sendOpus(packet(2)))
        assertEquals(1, ws.types().count { it == "floor_request" })
        rig.controller.releaseTalk()
        assertTrue(rig.controller.pressTalk())
        assertEquals(2, ws.types().count { it == "floor_request" })
        assertNotEquals(id, ws.requestId())
    }

    @Test(timeout = 60_000)
    fun aPressReleasedWhileWaitingNeverAsks() = runTest {
        val rig = Rig(this)
        val (ws, id) = talkingOnTwo(rig, frames = 2)
        rig.controller.releaseTalk()
        assertTrue(rig.controller.pressTalk())
        rig.controller.releaseTalk()
        ws.released(id, "released")
        runCurrent()
        assertEquals(1, ws.types().count { it == "floor_request" })
    }

    @Test(timeout = 60_000)
    fun aBacklogIsPacedTo200PerSecondAndHeldWhile16KbAreUnsent() = runTest {
        val rig = Rig(this)
        val (first, id) = talkingOnTwo(rig, frames = 0)
        first.serverClose(1006)
        runCurrent()
        repeat(300) { assertTrue(rig.controller.sendOpus(packet(it))) }
        rig.controller.releaseTalk()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1, protocol = 2)
        rig.joined(second, state = "answered")
        second.serverText("""{"type":"resume_from","last_seq":-1}""")
        runCurrent()
        assertEquals("the first second's worth", 200, second.binaries.size)
        second.queued = TalkSessionController.BACKLOG_LIMIT_BYTES
        advanceTimeBy(1_100)
        assertEquals("held while 16 KB are queued", 200, second.binaries.size)
        second.queued = 0
        advanceTimeBy(TalkSessionController.QUEUE_POLL_MS + 1)
        assertEquals((0 until 300).toList(), second.binaries.map(::seqOf))
        assertTrue(second.lastOf("floor_release").contains(id))
        assertEquals("floor_release", second.lastSent())
    }

    @Test(timeout = 60_000)
    fun aGrantWithThePeerAwayTalksAndTheGapIsShown() = runTest {
        val rig = Rig(this)
        val (ws, id) = talkingOnTwo(rig, frames = 2, peer = false)
        assertFalse(rig.controller.state.value.peerPresent)
        assertTrue(rig.controller.state.value.protocol2)
        assertEquals(TalkFloor.YOU, rig.controller.state.value.floor)
        assertEquals(2, ws.binaries.size)
        ws.serverText("""{"type":"listener_gap","transmission_id":"$id"}""")
        runCurrent()
        assertEquals(OwnNotice.NOT_HEARD_LIVE, rig.controller.state.value.ownNotice)
        rig.controller.releaseTalk()
        ws.released(id, "released")
        runCurrent()
        assertEquals(
            "the line stays after the transmission",
            OwnNotice.NOT_HEARD_LIVE,
            rig.controller.state.value.ownNotice
        )
    }

    @Test(timeout = 60_000)
    fun aProtocolOneServerGetsMilestoneFourTalk() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val first = rig.connect(protocol = 1)
        assertTrue(first.texts.first().contains("\"protocol\":2"))
        rig.joined(first, state = "answered")
        val events = collectEvents(rig)
        assertTrue(rig.controller.pressTalk())
        val id = first.requestId()
        assertEquals("""{"type":"floor_request","transmission_id":"$id"}""", first.lastOf("floor_request"))
        first.grant(OWN, id)
        runCurrent()
        assertTrue(rig.controller.sendOpus(packet(0)))
        first.serverClose(1006)
        runCurrent()
        assertTrue(events.contains(TalkEvent.StopCapture))
        assertFalse(rig.controller.state.value.capturing)
        assertFalse(rig.controller.sendOpus(packet(1)))
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1, protocol = 1)
        rig.joined(second, state = "answered")
        val sent = (first.types() + second.types()).toSet()
        for (type in listOf("resume", "heard", "sender_key")) assertFalse(type, type in sent)
    }

    @Test(timeout = 60_000)
    fun aReconnectOnProtocolOneGivesUpTheOpenTransmission() = runTest {
        val rig = Rig(this)
        val (first, _) = talkingOnTwo(rig, frames = 3)
        first.serverClose(1006)
        runCurrent()
        advanceTimeBy(TalkSessionController.RECONNECT_CAP_MS)
        val second = rig.connect(1, protocol = 1)
        rig.joined(second, state = "answered")
        assertFalse(second.types().contains("resume"))
        assertFalse(rig.controller.state.value.sending)
        assertEquals(OwnNotice.NOT_SENT, rig.controller.state.value.ownNotice)
    }

    @Test(timeout = 60_000)
    fun anOwnGrantRefusedByRuleFourBuzzes() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = 2)
        rig.joined(ws, state = "answered")
        val events = collectEvents(rig)
        assertTrue(rig.controller.pressTalk())
        val id = ws.requestId()
        // The peer's transmission is current when (a lying server) grants this phone too.
        ws.grant(PEER_DEVICE, "77777777-7777-4777-8777-777777777777")
        ws.grant(OWN, id)
        runCurrent()
        assertTrue(ws.lastOf("floor_release").contains(id))
        assertTrue(events.contains(TalkEvent.FloorBusy))
        assertFalse(rig.controller.sendOpus(packet(0)))
    }

    // ---- clip mode (section 13.9) ----

    /** A pong for no ping: keeps a joined protocol-2 socket alive without being a sample. */
    private fun FakeWs.keepAlive() = serverText("""{"type":"pong","t":1,"server_time":1}""")

    /** Three pings in a row unanswered for 800 ms (at 0.8, 2.8 and 4.8 s after the join). */
    private fun TestScope.slowPings(ws: FakeWs) {
        advanceTimeBy(2 * TalkSocket.CLIP_PING_INTERVAL_MS + RttSamples.TIMEOUT_MS + 1)
        ws.keepAlive()
        runCurrent()
    }

    /** Answers each of the next [n] pings within 100 ms (each one calm sample). */
    private fun TestScope.fastPings(ws: FakeWs, n: Int) = repeat(n) {
        val before = ws.types().count { it == "ping" }
        while (ws.types().count { it == "ping" } == before) advanceTimeBy(100)
        val t = Regex("\"t\":(\\d+)").find(ws.lastOf("ping"))!!.groupValues[1]
        ws.serverText("""{"type":"pong","t":$t,"server_time":1}""")
        runCurrent()
    }

    /** The answerer joined on [protocol] (and the peer with it), then in clip mode by slow pings on protocol 2. */
    private suspend fun TestScope.inClipMode(rig: Rig, protocol: Int = 2): FakeWs {
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val ws = rig.connect(protocol = protocol)
        rig.joined(ws, state = "answered")
        assertFalse(rig.controller.state.value.clipMode)
        slowPings(ws)
        return ws
    }

    /** Drops [ws] and joins again on the next socket within the first backoff steps (a few seconds). */
    private fun TestScope.quickReconnect(rig: Rig, ws: FakeWs, index: Int, protocol: Int = 2): FakeWs {
        ws.serverClose(1006)
        runCurrent()
        advanceTimeBy(2 * Backoff.BASE_MS + 1)
        val next = rig.connect(index, protocol = protocol)
        rig.joined(next, state = "answered")
        return next
    }

    @Test(timeout = 60_000)
    fun slowPingsTurnClipModeOnAndAPressRecordsAtOnceButSendsOnlyAfterTheGrant() = runTest {
        val rig = Rig(this)
        val ws = inClipMode(rig)
        assertTrue(rig.controller.state.value.clipMode)
        val events = collectEvents(rig)
        assertTrue(rig.controller.pressTalk())
        val id = ws.requestId()
        assertEquals("""{"type":"floor_request","transmission_id":"$id","clip":true}""", ws.lastOf("floor_request"))
        runCurrent()
        assertTrue(events.contains(TalkEvent.RecordingClip))
        assertTrue("recording before the grant", rig.controller.state.value.capturing)
        assertTrue("nothing plays meanwhile", rig.controller.state.value.sending)
        assertEquals(TalkFloor.IDLE, rig.controller.state.value.floor)
        repeat(5) { assertTrue(rig.controller.sendOpus(packet(it))) }
        assertTrue("nothing is sent before the grant", ws.binaries.isEmpty())
        ws.grant(OWN, id)
        runCurrent()
        assertEquals("the backlog goes at the grant", (0..4).toList(), ws.binaries.map(::seqOf))
        assertFalse("the press already chirped", events.contains(TalkEvent.FloorGrantedToYou))
        assertEquals(TalkFloor.YOU, rig.controller.state.value.floor)
        assertTrue(rig.controller.sendOpus(packet(5)))
        assertEquals("then frames as captured", (0..5).toList(), ws.binaries.map(::seqOf))
        rig.controller.releaseTalk()
        assertEquals("floor_release", ws.lastSent())
        assertTrue(ws.lastOf("floor_release").contains(id))
    }

    @Test(timeout = 60_000)
    fun aClipLetGoBeforeItsGrantIsSentWholeAfterIt() = runTest {
        val rig = Rig(this)
        val ws = inClipMode(rig)
        assertTrue(rig.controller.pressTalk())
        val id = ws.requestId()
        repeat(3) { assertTrue(rig.controller.sendOpus(packet(it))) }
        rig.controller.releaseTalk()
        val waiting = rig.controller.state.value
        assertFalse(waiting.capturing)
        assertTrue("nothing plays while it waits to be sent", waiting.sending)
        assertEquals(OwnNotice.SENDING_AS_CLIP, waiting.ownNotice)
        assertFalse("the microphone is off", rig.controller.sendOpus(packet(3)))
        assertFalse(ws.types().contains("floor_release"))
        assertTrue(ws.binaries.isEmpty())
        ws.grant(OWN, id)
        runCurrent()
        assertEquals((0..2).toList(), ws.binaries.map(::seqOf))
        assertEquals("floor_release", ws.lastSent())
        assertTrue(ws.lastOf("floor_release").contains(id))
        assertEquals("let go: not talking", TalkFloor.IDLE, rig.controller.state.value.floor)
        assertFalse(rig.controller.state.value.sending)
        assertFalse(rig.controller.state.value.capturing)
        ws.released(id, "released")
        runCurrent()
        assertEquals(OwnNotice.NONE, rig.controller.state.value.ownNotice)
        assertTrue(rig.controller.pressTalk())
        assertNotEquals(id, ws.requestId())
    }

    /** A clip-mode press with three frames recorded, answered with [refusal]: discarded unsent, the mic off. */
    private suspend fun TestScope.refusedClip(refusal: String) {
        val rig = Rig(this)
        val ws = inClipMode(rig)
        val events = collectEvents(rig)
        assertTrue(rig.controller.pressTalk())
        repeat(3) { assertTrue(rig.controller.sendOpus(packet(it))) }
        ws.serverText(refusal)
        runCurrent()
        val state = rig.controller.state.value
        assertTrue(events.contains(TalkEvent.StopCapture))
        assertFalse(state.capturing)
        assertFalse(state.sending)
        assertEquals(OwnNotice.NOT_SENT, state.ownNotice)
        assertFalse(rig.controller.sendOpus(packet(3)))
        assertTrue(ws.binaries.isEmpty())
    }

    @Test(timeout = 60_000)
    fun aBusyClipIsDiscardedUnsentAndItsMicrophoneStops() = runTest { refusedClip("""{"type":"floor_busy"}""") }

    @Test(timeout = 60_000)
    fun anErrorForAClipRequestDiscardsItUnsentAndItsMicrophoneStops() = runTest {
        refusedClip("""{"type":"error","code":"bad_transmission_id"}""")
    }

    @Test(timeout = 60_000)
    fun aClipGrantRefusedWhileThePeerHoldsTheFloorIsNotSent() = runTest {
        val rig = Rig(this)
        val ws = inClipMode(rig)
        val events = collectEvents(rig)
        peerTalks(ws)
        assertTrue(rig.controller.pressTalk())
        val id = ws.requestId()
        repeat(3) { assertTrue(rig.controller.sendOpus(packet(it))) }
        ws.grant(OWN, id)
        runCurrent()
        assertTrue(ws.lastOf("floor_release").contains(id))
        assertTrue(events.contains(TalkEvent.FloorBusy))
        val state = rig.controller.state.value
        assertFalse(state.capturing)
        assertFalse(state.sending)
        assertEquals(OwnNotice.NOT_SENT, state.ownNotice)
        assertTrue(ws.binaries.isEmpty())
    }

    @Test(timeout = 60_000)
    fun aClipPressPendingAcrossASocketDropRecordsAndIsSentWhenItFires() = runTest {
        val rig = Rig(this)
        val first = inClipMode(rig)
        val events = collectEvents(rig)
        assertTrue(rig.controller.pressTalk())
        val previous = first.requestId()
        assertTrue(rig.controller.sendOpus(packet(0)))
        rig.controller.releaseTalk()
        assertTrue("waits for the previous clip", rig.controller.pressTalk())
        assertFalse(rig.controller.state.value.capturing)
        val second = quickReconnect(rig, first, 1)
        assertTrue(second.lastOf("resume").contains(previous))
        second.serverText("""{"type":"resume_from","last_seq":-1}""")
        second.grant(OWN, previous)
        runCurrent()
        assertEquals(listOf(0), second.binaries.map(::seqOf))
        assertFalse(rig.controller.state.value.capturing)
        second.released(previous, "released")
        runCurrent()
        val next = second.requestId()
        assertNotEquals(previous, next)
        assertTrue(second.lastOf("floor_request").contains("\"clip\":true"))
        val state = rig.controller.state.value
        assertTrue("the waiting press records now", state.capturing)
        assertEquals(TalkConnection.LIVE, state.connection)
        assertEquals("the audio is told to record", 2, events.count { it == TalkEvent.RecordingClip })
        repeat(2) { assertTrue(rig.controller.sendOpus(packet(it))) }
        assertEquals("nothing before its grant", 1, second.binaries.size)
        second.grant(OWN, next)
        runCurrent()
        assertEquals(listOf(0, 0, 1), second.binaries.map(::seqOf))
    }

    @Test(timeout = 60_000)
    fun theBacklogOfAClipGrantIsPaced() = runTest {
        val rig = Rig(this)
        val ws = inClipMode(rig)
        assertTrue(rig.controller.pressTalk())
        val id = ws.requestId()
        repeat(300) { assertTrue(rig.controller.sendOpus(packet(it))) }
        rig.controller.releaseTalk()
        ws.grant(OWN, id)
        runCurrent()
        assertEquals("the first second's worth", 200, ws.binaries.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals((0 until 300).toList(), ws.binaries.map(::seqOf))
        assertEquals("floor_release", ws.lastSent())
    }

    @Test(timeout = 60_000)
    fun aClipRequestLostWithItsSocketKeepsRecordingAndIsSentAfterTheResume() = runTest {
        val rig = Rig(this)
        val first = inClipMode(rig)
        val events = collectEvents(rig)
        assertTrue(rig.controller.pressTalk())
        val id = first.requestId()
        repeat(2) { assertTrue(rig.controller.sendOpus(packet(it))) }
        first.serverClose(1006)
        runCurrent()
        assertTrue("still recording (section 13.6)", rig.controller.state.value.capturing)
        assertFalse(events.contains(TalkEvent.StopCapture))
        assertTrue(rig.controller.sendOpus(packet(2)))
        advanceTimeBy(2 * Backoff.BASE_MS + 1)
        val second = rig.connect(1, protocol = 2)
        rig.joined(second, state = "answered")
        assertTrue(second.lastOf("resume").contains(id))
        assertTrue(second.binaries.isEmpty())
        second.serverText("""{"type":"resume_from","last_seq":-1}""")
        runCurrent()
        assertEquals((0..2).toList(), second.binaries.map(::seqOf))
        rig.controller.releaseTalk()
        assertTrue(second.lastOf("floor_release").contains(id))
    }

    @Test(timeout = 60_000)
    fun aClipRequestTheServerNeverGotIsNotSentAndItsMicrophoneStops() = runTest {
        val rig = Rig(this)
        val first = inClipMode(rig)
        val events = collectEvents(rig)
        assertTrue(rig.controller.pressTalk())
        repeat(2) { assertTrue(rig.controller.sendOpus(packet(it))) }
        val second = quickReconnect(rig, first, 1)
        assertTrue(second.types().contains("resume"))
        second.serverText("""{"type":"error","code":"bad_transmission_id"}""")
        runCurrent()
        assertTrue(events.contains(TalkEvent.StopCapture))
        assertFalse(rig.controller.state.value.capturing)
        assertEquals(OwnNotice.NOT_SENT, rig.controller.state.value.ownNotice)
        assertTrue(second.binaries.isEmpty())
    }

    @Test(timeout = 60_000)
    fun theSessionEndingStopsAClipRecordingAndSendsNothing() = runTest {
        val rig = Rig(this)
        val ws = inClipMode(rig)
        assertTrue(rig.controller.pressTalk())
        assertTrue(rig.controller.sendOpus(packet(0)))
        ws.serverText("""{"type":"session_end","reason":"ended_by_peer"}""")
        runCurrent()
        assertFalse(rig.controller.state.value.capturing)
        assertFalse(rig.controller.sendOpus(packet(1)))
        assertTrue(ws.binaries.isEmpty())
    }

    @Test(timeout = 60_000)
    fun aModeChangeAppliesFromTheNextPress() = runTest {
        val rig = Rig(this)
        val (ws, live) = talkingOnTwo(rig, frames = 1)
        slowPings(ws)
        assertTrue(rig.controller.state.value.clipMode)
        assertTrue("the live transmission stays live", rig.controller.sendOpus(packet(1)))
        assertEquals(listOf(0, 1), ws.binaries.map(::seqOf))
        rig.controller.releaseTalk()
        ws.released(live, "released")
        runCurrent()
        assertTrue(rig.controller.pressTalk())
        val clip = ws.requestId()
        assertTrue(ws.lastOf("floor_request").contains("\"clip\":true"))
        assertTrue(rig.controller.sendOpus(packet(0)))
        fastPings(ws, ClipModeDetector.LEAVE_SAMPLES)
        assertFalse(rig.controller.state.value.clipMode)
        assertTrue("the clip stays a clip", rig.controller.sendOpus(packet(1)))
        assertEquals(2, ws.binaries.size)
        rig.controller.releaseTalk()
        ws.grant(OWN, clip)
        runCurrent()
        assertEquals(listOf(0, 1, 0, 1), ws.binaries.map(::seqOf))
        ws.released(clip, "released")
        runCurrent()
        assertTrue(rig.controller.pressTalk())
        assertTrue(ws.lastOf("floor_request").contains("\"clip\":false"))
    }

    @Test(timeout = 60_000)
    fun twoReconnectsWithinThirtySecondsTurnClipModeOn() = runTest {
        val rig = Rig(this)
        rig.receiver()
        rig.accepted(OutboxKind.ANSWER)
        rig.controller.open(PAGE)
        val first = rig.connect(protocol = 2)
        rig.joined(first, state = "answered")
        val second = quickReconnect(rig, first, 1)
        assertFalse("one reconnect", rig.controller.state.value.clipMode)
        quickReconnect(rig, second, 2)
        assertTrue(rig.controller.state.value.clipMode)
    }

    @Test(timeout = 60_000)
    fun protocolOneNeverEntersClipMode() = runTest {
        val rig = Rig(this)
        val first = inClipMode(rig, protocol = 1)
        val second = quickReconnect(rig, first, 1, protocol = 1)
        quickReconnect(rig, second, 2, protocol = 1)
        assertFalse(rig.controller.state.value.clipMode)
        assertTrue(rig.controller.pressTalk())
        assertFalse(rig.ws(2).lastOf("floor_request").contains("clip"))
    }

    @Test(timeout = 60_000)
    fun aReconnectOnProtocolOneLeavesClipModeAndGivesUpAClipRecording() = runTest {
        val rig = Rig(this)
        val first = inClipMode(rig)
        assertTrue(rig.controller.pressTalk())
        assertTrue(rig.controller.sendOpus(packet(0)))
        quickReconnect(rig, first, 1, protocol = 1)
        val state = rig.controller.state.value
        assertFalse(state.clipMode)
        assertFalse(state.capturing)
        assertEquals(OwnNotice.NOT_SENT, state.ownNotice)
    }

    @Test(timeout = 60_000)
    fun notHeardLiveClearsWhenThePeerIsBack() = runTest {
        val rig = Rig(this)
        val (ws, id) = talkingOnTwo(rig, frames = 2, peer = false)
        ws.serverText("""{"type":"listener_gap","transmission_id":"$id"}""")
        rig.controller.releaseTalk()
        ws.released(id, "released")
        runCurrent()
        assertEquals(OwnNotice.NOT_HEARD_LIVE, rig.controller.state.value.ownNotice)
        ws.serverText("""{"type":"peer_joined","device_id":"$PEER_DEVICE"}""")
        runCurrent()
        assertEquals(OwnNotice.NONE, rig.controller.state.value.ownNotice)
    }
}
