package com.example.ptt.talk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueExecutorTest {
    @Test(timeout = 60_000)
    fun cuesPastTheQueueBoundAreDropped() {
        val executor = boundedCueExecutor(maxQueued = 2)
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val ran = AtomicInteger()
        try {
            executor.execute {
                started.countDown()
                gate.await()
                ran.incrementAndGet()
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            repeat(5) { executor.execute { ran.incrementAndGet() } }
            gate.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals("one running + two queued; the other three dropped", 3, ran.get())
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }
}
