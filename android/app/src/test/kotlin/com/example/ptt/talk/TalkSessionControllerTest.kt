package com.example.ptt.talk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ptt.crypto.AudioSession
import com.example.ptt.crypto.GrantOutcome
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.TestDb
import com.example.ptt.net.CloseReaction
import com.example.ptt.net.FakeTalkAuth
import com.example.ptt.net.FakeTalkTransport
import com.example.ptt.net.FakeWs
import com.example.ptt.net.InMemoryDeviceTokenStore
import com.example.ptt.net.OWN
import com.example.ptt.net.PEER_DEVICE
import com.example.ptt.net.SessionEndReason
import com.example.ptt.net.WS_TOKEN
import com.example.ptt.net.WsPageState
import com.example.ptt.net.serverHello
import com.example.ptt.outbox.OutboxOutcome
import com.example.ptt.push.SelfPages
import com.example.ptt.push.SessionKeys
import com.example.ptt.push.SocketPageEvents
import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val PAGE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val SESSION = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val STRANGER = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private val KEY = ByteArray(32) { (it + 1).toByte() }

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
        fun connect(index: Int = 0): FakeWs {
            test.testScheduler.runCurrent()
            val ws = ws(index)
            ws.serverOpen()
            ws.serverText(serverHello())
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
        assertEquals(listOf("hello", "join", "ping"), ws.types())
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
        backgroundScope.launch { rig.controller.incomingOpus.collect { got += it } }
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
}
