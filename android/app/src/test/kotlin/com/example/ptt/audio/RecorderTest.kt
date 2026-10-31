package com.example.ptt.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
    private val sent = SentLog()
    private val clock = AtomicLong()
    private val sources = CopyOnWriteArrayList<FakeSource>()
    private val encoders = CopyOnWriteArrayList<FakeEncoder>()
    private val stops = CopyOnWriteArrayList<StopReason>()
    private var sourceFails = false
    private var encoderFails = false

    private fun recorder(paceMs: Long = 1, prewarmMs: Long = 10_000) = Recorder(
        sendOpus = sent::send,
        sourceFactory = {
            if (sourceFails) error("no mic")
            FakeSource(clock, paceMs).also { sources += it }
        },
        encoderFactory = {
            if (encoderFails) error("no codec")
            FakeEncoder().also { encoders += it }
        },
        clock = clock::get,
        prewarmTimeoutMs = prewarmMs,
        onStopped = { stops += it }
    )

    @Test(timeout = 60_000)
    fun prewarmOpensTheDevicesButRecordsAndSendsNothing() {
        val r = recorder()
        r.prewarm()
        await("devices opened") { sources.size == 1 && encoders.size == 1 }
        Thread.sleep(100)
        assertFalse(sources.single().started)
        assertEquals(0, sources.single().reads.get())
        assertEquals(0, sent.threads.size)
        assertEquals(Recorder.Phase.PREWARM, r.phase)
        r.stop()
        await("closed") { sources.single().closed && encoders.single().closed }
        assertEquals(0, sent.threads.size)
        assertFalse(sources.single().started)
        assertTrue("a requested stop reports nothing", stops.isEmpty())
    }

    @Test(timeout = 60_000)
    fun startSendsEveryFrameFromOneDedicatedThread() {
        val r = recorder()
        r.prewarm()
        r.start()
        await("some packets") { sent.count.get() >= 25 }
        r.stop()
        await("closed") { r.phase == Recorder.Phase.DONE }
        assertTrue(sources.single().closed && encoders.single().closed)
        val threads = sent.threads.toSet()
        assertEquals(1, threads.size)
        assertEquals("ptt-capture", threads.single().name)
        assertTrue(threads.single() !== Thread.currentThread())
    }

    @Test(timeout = 60_000)
    fun startWithoutPrewarmWorks() {
        val r = recorder()
        r.start()
        await("packets") { sent.count.get() >= 3 }
        r.stop()
    }

    @Test(timeout = 60_000)
    fun capStopsAtAbout59AndAHalfSecondsOnTheClientClock() {
        val r = recorder(paceMs = 0)
        r.start()
        await("cap") { stops.isNotEmpty() }
        assertEquals(listOf(StopReason.MAX_DURATION), stops.toList())
        // 59.5 s of 20 ms frames.
        assertEquals(2975, sent.count.get())
        assertTrue(sources.single().closed && encoders.single().closed)
    }

    @Test(timeout = 60_000)
    fun sendOpusFalseStopsCapture() {
        val r = recorder()
        r.start()
        await("packets") { sent.count.get() >= 3 }
        sent.accept = false
        await("stopped") { stops.isNotEmpty() }
        assertEquals(listOf(StopReason.SEND_REFUSED), stops.toList())
        assertTrue(sources.single().closed)
    }

    @Test(timeout = 60_000)
    fun microphoneFailureStopsWithFailure() {
        val r = recorder()
        r.start()
        await("source") { sources.isNotEmpty() }
        sources.single().failAfter = 3
        await("stopped") { stops.isNotEmpty() }
        assertEquals(listOf(StopReason.MIC_UNAVAILABLE), stops.toList())
    }

    @Test(timeout = 60_000)
    fun deviceOpenFailureIsReported() {
        sourceFails = true
        recorder().start()
        await("stopped") { stops.isNotEmpty() }
        assertEquals(listOf(StopReason.MIC_UNAVAILABLE), stops.toList())
        assertEquals(0, sent.threads.size)
    }

    @Test(timeout = 60_000)
    fun stopBeforeStartNeverRecords() {
        val r = recorder()
        r.prewarm()
        r.stop()
        r.start()
        await("done") { r.phase == Recorder.Phase.DONE }
        assertTrue(sources.none { it.started })
        assertEquals(0, sent.threads.size)
    }

    @Test(timeout = 60_000)
    fun aPrewarmNeverFollowedByAGrantShutsItselfDown() {
        val r = recorder(prewarmMs = 100)
        r.prewarm()
        await("self shutdown") { r.phase == Recorder.Phase.DONE }
        assertTrue(sources.single().closed && encoders.single().closed)
        assertFalse(sources.single().started)
        assertEquals(0, sent.threads.size)
        assertTrue(stops.isEmpty())
    }

    @Test(timeout = 60_000)
    fun theCapCountsFromTheGrantNotFromTheFirstSample() {
        val r = recorder(paceMs = 0)
        // The grant reached the phone 59 s "ago" by the time capture runs: only half a second remains.
        r.start(grantedAtMs = -59_000)
        await("cap") { stops.isNotEmpty() }
        assertEquals(listOf(StopReason.MAX_DURATION), stops.toList())
        assertEquals(25, sent.count.get())
    }

    @Test(timeout = 60_000)
    fun encoderOpenFailureIsReportedAsCodecUnavailable() {
        encoderFails = true
        recorder().start()
        await("stopped") { stops.isNotEmpty() }
        assertEquals(listOf(StopReason.CODEC_UNAVAILABLE), stops.toList())
        assertTrue(sources.single().closed)
    }
}
