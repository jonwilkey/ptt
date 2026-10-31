package com.example.ptt.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TalkSocketTest {
    private val transport = FakeTalkTransport()
    private val auth = FakeTalkAuth()

    private fun kotlinx.coroutines.test.TestScope.socket() =
        TalkSocket(transport, auth, "0.3.0", backgroundScope) { testScheduler.currentTime }

    @Test(timeout = 60_000)
    fun helloIsTheFirstFrameAndNothingElseGoesBeforeIt() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        assertFalse(s.send(ClientMessage.Join(OWN)))
        ws.serverOpen()
        assertEquals(listOf("hello"), ws.types())
        assertTrue(ws.texts.single().contains("\"protocol\":1"))
        assertFalse("not before hello is answered", s.send(ClientMessage.Join(OWN)))
        ws.serverText(serverHello())
        assertEquals(SocketEvent.Ready(OWN, 1), s.events.receive())
        runCurrent()
        assertTrue(s.send(ClientMessage.Join(OWN)))
        assertEquals(listOf("hello", "ping", "join"), ws.types())
    }

    @Test(timeout = 60_000)
    fun protocolIsTheLowerOfBothSides() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        ws.serverText(serverHello(protocol = 0 + 1))
        assertEquals(1, (s.events.receive() as SocketEvent.Ready).protocol)
    }

    @Test(timeout = 60_000)
    fun pingsEveryTenSecondsAndMeasuresRtt() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        ws.serverText(serverHello())
        s.events.receive()
        runCurrent()
        assertEquals(1, ws.types().count { it == "ping" })
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, ws.types().count { it == "ping" })
        // The pong echoes t; 40 ms later the RTT is 40.
        ws.serverText("""{"type":"pong","t":${testScheduler.currentTime - 40},"server_time":1}""")
        assertEquals(SocketEvent.Rtt(40), s.events.receive())
        // Keep the connection alive and check the cadence continues.
        advanceTimeBy(9_000)
        ws.serverText("""{"type":"pong","t":${testScheduler.currentTime},"server_time":1}""")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, ws.types().count { it == "ping" })
    }

    @Test(timeout = 60_000)
    fun messagesArriveInSocketOrderAndUnknownOnesAreSkipped() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        ws.serverText(serverHello())
        ws.serverText("""{"type":"future_thing","x":1}""")
        ws.serverText("""{"type":"peer_joined","device_id":"$PEER_DEVICE"}""")
        ws.serverBinary(byteArrayOf(1, 2, 3))
        ws.serverText("""{"type":"floor_busy"}""")
        assertTrue(s.events.receive() is SocketEvent.Ready)
        assertEquals(SocketEvent.Message(ServerMessage.PeerJoined(PEER_DEVICE)), s.events.receive())
        assertTrue(s.events.receive() is SocketEvent.Frame)
        assertEquals(SocketEvent.Message(ServerMessage.FloorBusy), s.events.receive())
    }

    @Test(timeout = 60_000)
    fun framesBeforeHelloAreNotDelivered() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        ws.serverBinary(byteArrayOf(1))
        ws.serverText("""{"type":"floor_busy"}""")
        ws.serverClose(4503)
        assertEquals(SocketEvent.Closed(CloseReaction.RECONNECT, 4503, null), s.events.receive())
    }

    @Test(timeout = 60_000)
    fun unauthorizedCloseFlagsTheTokenThatWasSent() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        ws.serverClose(4401)
        assertEquals(SocketEvent.Closed(CloseReaction.UNAUTHORIZED, 4401, null), s.events.receive())
        assertEquals(listOf(WS_TOKEN), auth.rejected)
        assertNull(s.events.receiveCatching().getOrNull())
    }

    @Test(timeout = 60_000)
    fun upgrade404MeansTalkUnavailable() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        transport.sockets.single().failUpgrade(404)
        assertEquals(SocketEvent.Closed(CloseReaction.UNAVAILABLE, null, 404), s.events.receive())
    }

    @Test(timeout = 60_000)
    fun noHelloAnswerIsADeadSocket() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        advanceTimeBy(TalkSocket.HELLO_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(s.events.receive() is SocketEvent.Closed)
        assertTrue(ws.cancelled)
    }

    @Test(timeout = 60_000)
    fun silenceFromTheServerEndsTheConnection() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        ws.serverText(serverHello())
        s.events.receive()
        advanceTimeBy(TalkSocket.DEAD_AFTER_MS + TalkSocket.PING_INTERVAL_MS)
        runCurrent()
        assertTrue(s.events.receive() is SocketEvent.Closed)
    }

    @Test(timeout = 60_000)
    fun notEnrolledOrNoTokenNeverOpensASocket() = runTest {
        transport.enrolled = false
        val a = socket()
        a.open()
        runCurrent()
        assertEquals(CloseReaction.UNAVAILABLE, (a.events.receive() as SocketEvent.Closed).reaction)
        assertTrue(transport.sockets.isEmpty())

        transport.enrolled = true
        auth.token = null
        val b = socket()
        b.open()
        runCurrent()
        assertEquals(CloseReaction.UNAUTHORIZED, (b.events.receive() as SocketEvent.Closed).reaction)
        assertTrue(transport.sockets.isEmpty())
        assertTrue("no token to reject", auth.rejected.isEmpty())
    }

    @Test(timeout = 60_000)
    fun clientCloseIsGracefulAndEndsTheEventStream() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        ws.serverText(serverHello())
        s.events.receive()
        s.close()
        assertEquals(1000, ws.closedWith)
        assertTrue(s.events.receive() is SocketEvent.Closed)
    }

    @Test(timeout = 60_000)
    fun audioFramesGoOutAsBinaryOnlyWhenReady() = runTest {
        val s = socket()
        s.open()
        runCurrent()
        val ws = transport.sockets.single()
        ws.serverOpen()
        assertFalse(s.sendFrame(byteArrayOf(1)))
        ws.serverText(serverHello())
        assertTrue(s.sendFrame(byteArrayOf(1, 2)))
        assertEquals(1, ws.binaries.size)
    }
}
