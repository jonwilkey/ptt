package com.example.ptt.net

import android.content.Intent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImpairedTransportTest {
    private val fake = FakeTalkTransport()
    private var setting = Impairment()
    private val seen = LinkedBlockingQueue<String>()
    private lateinit var ws: FakeWs
    private lateinit var socket: TransportSocket

    private val listener = object : TransportListener {
        override fun onOpen(socket: TransportSocket) {
            seen += "open"
        }

        override fun onText(text: String) {
            seen += "t:$text"
        }

        override fun onBinary(bytes: ByteArray) {
            seen += "b:${bytes.single()}"
        }

        override fun onClosed(code: Int?, httpStatus: Int?) {
            seen += "closed:$code"
        }
    }

    @Before
    fun setUp() {
        DebugImpairment.set(Impairment())
        open(Random(1))
    }

    @After
    fun tearDown() = DebugImpairment.set(Impairment())

    private fun open(random: Random) {
        val transport = ImpairedTransport(fake, { setting }, random)
        socket = runBlocking { transport.open(listener) }!!
        ws = fake.sockets.last()
        ws.serverOpen()
        assertEquals("open", seen.poll(5, TimeUnit.SECONDS))
    }

    private fun drain(count: Int): List<String> = List(count) { seen.poll(5, TimeUnit.SECONDS) ?: "timeout" }

    @Test(timeout = 60_000)
    fun withNothingSetEverythingPassesStraightThrough() {
        ws.serverText("a")
        ws.serverBinary(byteArrayOf(1))
        assertEquals(listOf("t:a", "b:1"), seen.toList())
        assertTrue(socket.sendText("x"))
        assertTrue(socket.sendBinary(byteArrayOf(2)))
        assertEquals(listOf("x"), ws.texts)
        assertEquals(1, ws.binaries.size)
    }

    @Test(timeout = 60_000)
    fun incomingIsDelayedAndKeepsOrderEvenWhenTheDelayShrinks() {
        setting = Impairment(delayMs = 1000)
        val start = System.nanoTime()
        ws.serverText("1")
        ws.serverBinary(byteArrayOf(2))
        setting = Impairment(delayMs = 10)
        ws.serverText("3")
        setting = Impairment()
        ws.serverText("4")
        assertTrue(seen.isEmpty())
        assertEquals(listOf("t:1", "b:2", "t:3", "t:4"), drain(4))
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(950))
    }

    @Test(timeout = 60_000)
    fun outgoingIsDelayedKeepsOrderAndIsNeverDropped() {
        setting = Impairment(delayMs = 1000, lossPct = 100)
        val start = System.nanoTime()
        assertTrue(socket.sendText("a"))
        assertTrue(socket.sendBinary(byteArrayOf(1)))
        assertTrue(socket.sendText("b"))
        assertEquals(1L, socket.queuedBytes())
        assertTrue(ws.texts.isEmpty() && ws.binaries.isEmpty())
        socket.close(1000)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ws.closedWith == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(950))
        assertEquals(listOf("a", "b"), ws.texts)
        assertEquals(1, ws.binaries.size)
        assertEquals(1000, ws.closedWith)
        assertEquals(0, socket.queuedBytes())
    }

    @Test(timeout = 60_000)
    fun lossHitsOnlyIncomingBinaryFrames() {
        setting = Impairment(lossPct = 100)
        ws.serverText("a")
        ws.serverBinary(byteArrayOf(1))
        ws.serverText("b")
        assertTrue(socket.sendBinary(byteArrayOf(2)))
        assertTrue(socket.sendText("c"))
        assertEquals(listOf("t:a", "t:b"), seen.toList())
        assertEquals(1, ws.binaries.size)
        assertEquals(listOf("c"), ws.texts)
    }

    @Test(timeout = 60_000)
    fun lossRateMatchesTheSetting() {
        setting = Impairment(lossPct = 5)
        repeat(10_000) { ws.serverBinary(byteArrayOf(1)) }
        val lost = 10_000 - seen.size
        assertTrue("lost $lost of 10000", lost in 350..650)
        setting = Impairment(lossPct = 0)
        seen.clear()
        repeat(1_000) { ws.serverBinary(byteArrayOf(1)) }
        assertEquals(1_000, seen.size)
    }

    @Test(timeout = 60_000)
    fun theCloseArrivesAfterTheMessagesBeforeIt() {
        setting = Impairment(delayMs = 50)
        ws.serverText("last")
        setting = Impairment()
        ws.serverClose(4409)
        assertEquals(listOf("t:last", "closed:4409"), drain(2))
    }

    @Test(timeout = 60_000)
    fun aCancelledSocketDeliversAndSendsNothingMore() {
        setting = Impairment(delayMs = 1000)
        ws.serverText("late")
        assertTrue(socket.sendText("late"))
        socket.cancel()
        assertFalse(socket.sendText("after"))
        Thread.sleep(300)
        assertTrue(seen.isEmpty())
        assertTrue(ws.texts.isEmpty())
        assertTrue(ws.cancelled)
    }

    @Test
    fun theBroadcastSetsAndClearsTheSetting() {
        val receiver = ImpairmentReceiver()
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        receiver.onReceive(
            context,
            Intent().putExtra("delay_ms", 300).putExtra("loss_pct", 5)
        )
        assertEquals(Impairment(300, 5), DebugImpairment.state.value)
        receiver.onReceive(context, Intent().putExtra("loss_pct", 0))
        assertEquals(Impairment(300, 0), DebugImpairment.state.value)
        receiver.onReceive(context, Intent().putExtra("delay_ms", 99_999).putExtra("loss_pct", 500))
        assertEquals(Impairment(10_000, 100), DebugImpairment.state.value)
        receiver.onReceive(context, Intent().putExtra("delay_ms", 0).putExtra("loss_pct", 0))
        assertFalse(DebugImpairment.state.value.active)
    }
}
