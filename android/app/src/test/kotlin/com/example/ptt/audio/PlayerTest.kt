package com.example.ptt.audio

import com.example.ptt.talk.IncomingOpus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes a packet to four samples all equal to its first byte (so tests can see what was played). */
private class FakeDecoder(val log: MutableList<String>, val fail: () -> Boolean = { false }) : OpusDecoder {
    var closed = false
    val decodedOn = CopyOnWriteArrayList<Thread>()

    override fun decode(opus: ByteArray): DecodedPcm {
        decodedOn += Thread.currentThread()
        if (fail()) throw java.io.IOException("boom")
        return DecodedPcm(ShortArray(4) { opus[0].toShort() }, 48_000)
    }

    override fun close() {
        closed = true
        log += "decoder closed"
    }
}

private class FakeSink(val rate: Int) : AudioSink {
    val writes = CopyOnWriteArrayList<List<Int>>()
    var closed = false
    val written = CountDownLatch(1)
    val calls = CopyOnWriteArrayList<String>()

    override fun start() {
        calls += "start"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun flush() {
        calls += "flush"
    }

    override fun write(pcm: ShortArray) {
        calls += "write"
        writes += pcm.map { it.toInt() }
        written.countDown()
    }

    override fun close() {
        closed = true
    }
}

class PlayerTest {
    private val log = CopyOnWriteArrayList<String>()
    private val decoders = CopyOnWriteArrayList<FakeDecoder>()
    private val sinks = CopyOnWriteArrayList<FakeSink>()
    private var failing = false
    private val player = Player(
        decoderFactory = { FakeDecoder(log) { failing }.also { decoders += it } },
        sinkFactory = { FakeSink(it).also { s -> sinks += s } },
        clock = { 0L }
    )

    private fun frame(tid: String, seq: Int, value: Int = seq + 1) = IncomingOpus(tid, seq, byteArrayOf(value.toByte()))

    private fun played() = sinks.flatMap { it.writes }.map { it[0] }

    @Test(timeout = 60_000)
    fun playsInSeqOrderEvenWhenFramesArriveReordered() {
        player.handleFrame(frame("a", 0), 0)
        player.handleFrame(frame("a", 2), 40)
        player.handleFrame(frame("a", 1), 45)
        player.tick(1000)
        assertEquals(listOf(1, 2, 3), played())
    }

    @Test(timeout = 60_000)
    fun aMissingPacketIsConcealedByTheLastFrameAttenuated() {
        player.handleFrame(frame("a", 0, 80), 0)
        player.handleFrame(frame("a", 5, 7), 10)
        player.tick(1000)
        // seq 1..4 lost: halved, quartered, eighth, then silence.
        assertEquals(listOf(80, 40, 20, 10, 0, 7), played())
        assertEquals(1, decoders.size)
    }

    @Test(timeout = 60_000)
    fun everyTransmissionGetsAFreshDecoderAndTheOldOneIsClosed() {
        player.handleFrame(frame("a", 0), 0)
        player.handleFrame(frame("b", 0, 9), 10)
        assertEquals(2, decoders.size)
        assertTrue(decoders[0].closed)
        player.tick(1000)
        assertEquals(listOf(9), played())
    }

    @Test(timeout = 60_000)
    fun afterReleaseTheBufferPlaysOutThenTheDecoderIsFreedAndLateFramesAreDropped() {
        player.handleFrame(frame("a", 0), 0)
        player.handleFrame(frame("a", 1), 20)
        player.handleRelease()
        player.handleFrame(frame("a", 2), 40)
        player.tick(1000)
        assertEquals(listOf(1, 2), played())
        assertTrue(decoders.single().closed)
    }

    @Test(timeout = 60_000)
    fun flushDiscardsWhatIsBufferedAndFreesTheDecoder() {
        player.handleFrame(frame("a", 0), 0)
        player.handleFlush()
        player.tick(1000)
        assertEquals(emptyList<Int>(), played())
        assertTrue(decoders.single().closed)
    }

    @Test(timeout = 60_000)
    fun decodeFailuresAreConcealedAndAPersistentlyBrokenDecoderIsDropped() {
        failing = true
        for (i in 0..7) player.handleFrame(frame("a", i), i * 20L)
        player.tick(1000)
        assertTrue(decoders.single().closed)
        assertTrue(played().all { it == 0 })
        assertEquals(8, played().size)
    }

    @Test(timeout = 60_000)
    fun enqueueOnlyQueuesAndPlayoutRunsOnOneDedicatedThread() {
        val real = Player(
            decoderFactory = { FakeDecoder(log).also { decoders += it } },
            sinkFactory = { FakeSink(it).also { s -> sinks += s } },
            clock = System::currentTimeMillis
        )
        real.enqueue(frame("a", 0))
        real.enqueue(frame("a", 1))
        // Nothing has been decoded by the caller's thread.
        assertTrue(decoders.isEmpty() || decoders.all { it.decodedOn.none { t -> t === Thread.currentThread() } })
        real.start()
        for (i in 2..4) real.enqueue(frame("a", i))
        assertTrue(sinks.isNotEmpty() || waitForSink())
        real.close()
        val threads = decoders.flatMap { it.decodedOn }.toSet()
        assertEquals(1, threads.size)
        assertEquals("ptt-playout", threads.single().name)
        assertTrue(sinks.single().closed)
        assertTrue(decoders.single().closed)
    }

    @Test(timeout = 60_000)
    fun theSinkStartsAtTheFirstWriteOfEachTransmissionAndStopsAtItsEnd() {
        player.handleFrame(frame("a", 0), 0)
        player.handleFrame(frame("a", 1), 20)
        player.handleRelease()
        player.tick(1000)
        player.handleFrame(frame("b", 0), 2000)
        player.handleRelease()
        player.tick(3000)
        assertEquals(1, sinks.size)
        assertEquals(
            listOf("start", "write", "write", "stop", "start", "write", "stop"),
            sinks.single().calls.toList()
        )
    }

    @Test(timeout = 60_000)
    fun flushDiscardsWhatTheSinkHoldsOnlyWhileItRuns() {
        player.handleFlush()
        assertTrue(sinks.isEmpty())
        player.handleFrame(frame("a", 0), 0)
        player.tick(1000)
        player.handleFlush()
        assertEquals(listOf("start", "write", "flush"), sinks.single().calls.toList())
    }

    @Test(timeout = 60_000)
    fun aSinkThatCannotBeBuiltDegradesToSilenceWithoutCrashing() {
        val p = Player(
            decoderFactory = { FakeDecoder(log) },
            sinkFactory = { throw UnsupportedOperationException("no track") },
            clock = { 0L }
        )
        p.handleFrame(frame("a", 0), 0)
        p.handleFrame(frame("a", 1), 20)
        p.tick(1000)
        p.close()
    }

    @Test(timeout = 60_000)
    fun anUnexpectedExceptionOnThePlayoutThreadDoesNotCrashTheProcess() {
        val crashed = java.util.concurrent.atomic.AtomicBoolean()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> crashed.set(true) }
        try {
            val p = Player(
                decoderFactory = { throw ArithmeticException("codec bug") },
                sinkFactory = { FakeSink(it) },
                clock = System::currentTimeMillis
            )
            p.start()
            p.enqueue(frame("a", 0))
            val t = p.playoutThread!!
            t.join(10_000)
            assertFalse(t.isAlive)
            assertFalse(crashed.get())
            p.close()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test(timeout = 60_000)
    fun aSinkFailureIsRetriedWithTheNextTransmission() {
        var attempts = 0
        val made = CopyOnWriteArrayList<FakeSink>()
        val p = Player(
            decoderFactory = { FakeDecoder(log) },
            sinkFactory = {
                if (attempts++ == 0) {
                    throw UnsupportedOperationException("dead")
                }
                FakeSink(it).also { s -> made += s }
            },
            clock = { 0L }
        )
        p.handleFrame(frame("a", 0), 0)
        p.tick(1000)
        assertTrue(made.isEmpty())
        p.handleFrame(frame("b", 0, 9), 2000)
        p.tick(3000)
        assertEquals(listOf(9), made.single().writes.map { it[0] })
    }

    @Test(timeout = 60_000)
    fun dropCurrentSilencesTheRestOfTheTransmissionButNotTheNext() {
        player.handleFrame(frame("a", 0), 0)
        player.tick(1000)
        player.handleFrame(frame("a", 1), 1010)
        player.handleDropCurrent()
        // The peer keeps talking: more frames of the same transmission must stay silent.
        player.handleFrame(frame("a", 2), 1020)
        player.handleFrame(frame("a", 3), 1040)
        player.tick(5000)
        assertEquals(listOf(1), played())
        assertEquals(listOf("start", "write", "flush"), sinks.single().calls.toList())
        player.handleFrame(frame("b", 0, 7), 6000)
        player.tick(7000)
        assertEquals(listOf(1, 7), played())
    }

    private fun waitForSink(): Boolean {
        val end = System.currentTimeMillis() + 10_000
        while (sinks.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        sinks.firstOrNull()?.written?.await(10, TimeUnit.SECONDS)
        return sinks.isNotEmpty()
    }
}
