package com.example.ptt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private val jb = JitterBuffer()

    private fun pkt(n: Int) = byteArrayOf(n.toByte())

    private fun JitterBuffer.drain(now: Long): List<String> {
        val out = ArrayList<String>()
        while (true) {
            when (val o = poll(now) ?: break) {
                is JitterOutput.Packet -> out += "P${o.seq}"
                is JitterOutput.Gap -> out += "G${o.count}"
            }
        }
        return out
    }

    @Test(timeout = 60_000)
    fun startsAt120msAndPlaysInOrderAtTheFramePace() {
        for (i in 0..4) jb.push("a", i, pkt(i), i * 20L)
        assertEquals(120, jb.currentDelayMs)
        assertEquals(emptyList<String>(), jb.drain(119))
        assertEquals(listOf("P0"), jb.drain(120))
        assertEquals(emptyList<String>(), jb.drain(139))
        assertEquals(listOf("P1", "P2"), jb.drain(160))
        assertEquals(listOf("P3", "P4"), jb.drain(500))
    }

    @Test(timeout = 60_000)
    fun reorderWithinTheWindowIsPlayedInOrder() {
        jb.push("a", 0, pkt(0), 0)
        jb.push("a", 2, pkt(2), 40)
        jb.push("a", 1, pkt(1), 45)
        assertEquals(listOf("P0", "P1", "P2"), jb.drain(200))
    }

    @Test(timeout = 60_000)
    fun reorderBeforeAnythingPlayedMayEvenPrecedeTheFirstPacketSeen() {
        jb.push("a", 5, pkt(5), 0)
        jb.push("a", 4, pkt(4), 10)
        assertEquals(listOf("P4", "P5"), jb.drain(300))
        assertEquals(0, jb.dropped)
    }

    @Test(timeout = 60_000)
    fun aMissingPacketIsReportedAsAGap() {
        jb.push("a", 0, pkt(0), 0)
        jb.push("a", 2, pkt(2), 40)
        jb.push("a", 3, pkt(3), 60)
        assertEquals(listOf("P0", "G1", "P2", "P3"), jb.drain(300))
    }

    @Test(timeout = 60_000)
    fun duplicatesAreDropped() {
        jb.push("a", 0, pkt(0), 0)
        jb.push("a", 0, pkt(9), 5)
        jb.push("a", 1, pkt(1), 20)
        assertEquals(1, jb.dropped)
        val out = ArrayList<Int>()
        while (true) {
            val o = jb.poll(500) as? JitterOutput.Packet ?: break
            out += o.opus[0].toInt()
        }
        assertEquals(listOf(0, 1), out)
    }

    @Test(timeout = 60_000)
    fun aLatePacketIsDroppedAndGrowsTheDelay() {
        for (i in 0..2) jb.push("a", i, pkt(i), i * 20L)
        assertEquals(listOf("P0", "P1", "P2"), jb.drain(200))
        jb.push("a", 1, pkt(1), 210)
        assertEquals(1, jb.dropped)
        assertEquals(140, jb.currentDelayMs)
        assertEquals(emptyList<String>(), jb.drain(500))
    }

    @Test(timeout = 60_000)
    fun aDryBufferRebuffersWithALongerDelay() {
        jb.push("a", 0, pkt(0), 0)
        jb.push("a", 1, pkt(1), 20)
        assertEquals(listOf("P0", "P1"), jb.drain(200))
        jb.push("a", 5, pkt(5), 1000)
        assertEquals(140, jb.currentDelayMs)
        assertEquals(emptyList<String>(), jb.drain(1139))
        assertEquals(listOf("P5"), jb.drain(1140))
    }

    @Test(timeout = 60_000)
    fun aLongOutageReanchorsInsteadOfPlayingManyGaps() {
        jb.push("a", 0, pkt(0), 0)
        jb.push("a", 10, pkt(10), 10)
        assertEquals(listOf("P0", "G1"), jb.drain(140))
        assertEquals(emptyList<String>(), jb.drain(259))
        assertEquals(listOf("P10"), jb.drain(260))
    }

    @Test(timeout = 60_000)
    fun delayStaysWithin60And300() {
        // Perfectly regular arrival: the estimate decays, the next transmission starts at the 60 ms floor.
        for (i in 0 until 200) jb.push("calm", i, pkt(i), i * 20L)
        jb.push("next", 0, pkt(0), 10_000)
        assertEquals(JitterBuffer.MIN_DELAY_MS, jb.currentDelayMs)

        // Wildly irregular arrival: capped at 300 ms, also under repeated growth during a transmission.
        var t = 20_000L
        for (i in 0 until 200) {
            t += if (i % 2 == 0) 0 else 200
            jb.push("wild", i, pkt(i), t)
        }
        jb.push("after", 0, pkt(0), t + 1000)
        assertEquals(JitterBuffer.MAX_DELAY_MS, jb.currentDelayMs)
        for (k in 1..30) {
            jb.push("after", k * 10, pkt(k), t + 1000 + k * 5000L)
            assertTrue(jb.currentDelayMs <= JitterBuffer.MAX_DELAY_MS)
        }
        assertEquals(JitterBuffer.MAX_DELAY_MS, jb.currentDelayMs)
    }

    @Test(timeout = 60_000)
    fun aNewTransmissionResetsEverything() {
        for (i in 0..2) jb.push("a", i, pkt(i), i * 20L)
        assertTrue(jb.push("b", 100, pkt(100), 50))
        assertEquals("b", jb.transmissionId)
        assertEquals(listOf("P100"), jb.drain(1000))
        // The old id's leftovers are gone.
        assertFalse(jb.push("b", 101, pkt(101), 1010))
    }

    @Test(timeout = 60_000)
    fun afterReleaseWhatIsBufferedPlaysOutAndNewFramesAreDropped() {
        for (i in 0..3) jb.push("a", i, pkt(i), i * 20L)
        jb.release()
        jb.push("a", 4, pkt(4), 80)
        assertEquals(1, jb.dropped)
        assertFalse(jb.drained)
        assertEquals(listOf("P0", "P1"), jb.drain(150))
        assertEquals(listOf("P2", "P3"), jb.drain(1000))
        assertTrue(jb.drained)
        assertNull(jb.poll(2000))
    }

    @Test(timeout = 60_000)
    fun flushDiscardsEverything() {
        for (i in 0..3) jb.push("a", i, pkt(i), i * 20L)
        jb.flush()
        assertNull(jb.poll(1000))
        assertNull(jb.transmissionId)
        assertTrue(jb.drained)
        // A fresh transmission of the same id works again.
        assertTrue(jb.push("a", 0, pkt(0), 2000))
        assertEquals(listOf("P0"), jb.drain(2200))
    }
}
