package com.example.ptt.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Polls [condition] for up to 20 s; fails the test with [what] otherwise. */
fun await(what: String, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + 20_000
    while (!condition()) {
        if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
        Thread.sleep(2)
    }
}

/** A microphone that never runs dry. [pace] sleeps in [read] so loops that wait for a trigger do not spin. */
class FakeSource(private val clock: AtomicLong? = null, private val paceMs: Long = 1) : PcmSource {
    @Volatile var started = false

    @Volatile var closed = false
    val reads = AtomicInteger()

    @Volatile var failAfter = Int.MAX_VALUE

    override fun start() {
        started = true
    }

    override fun read(buffer: ShortArray): Int {
        check(started) { "read before start" }
        if (reads.incrementAndGet() > failAfter) return -1
        clock?.addAndGet(AudioSpec.FRAME_MS.toLong())
        if (paceMs > 0) Thread.sleep(paceMs)
        return buffer.size
    }

    override fun close() {
        closed = true
    }
}

/** One 1-byte packet per frame. */
class FakeEncoder : OpusEncoder {
    @Volatile var closed = false
    private var n = 0

    override fun encode(pcm: ShortArray): List<ByteArray> = listOf(byteArrayOf((n++ and 0x7F).toByte()))

    override fun close() {
        closed = true
    }
}

class SentLog {
    val threads = CopyOnWriteArrayList<Thread>()
    val count = AtomicInteger()

    @Volatile var accept = true

    fun send(packet: ByteArray): Boolean {
        threads += Thread.currentThread()
        if (!accept) return false
        count.incrementAndGet()
        return packet.isNotEmpty()
    }
}
