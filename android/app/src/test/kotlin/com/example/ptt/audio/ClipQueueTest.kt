package com.example.ptt.audio

import java.util.BitSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clip playback order and timing (docs/protocol.md section 13.8), on a fake clock. */
class ClipQueueTest {
    private val q = ClipQueue()
    private var now = 0L

    private fun pkt(id: String, seq: Int) = q.offer(id, seq, byteArrayOf(seq.toByte()), now)

    /** A live frame as the player routes it: to the queue if its id is queued or gets held, else (false) live. */
    private fun live(id: String, seq: Int) = pkt(id, seq) || (q.hold(id, now) && pkt(id, seq))

    private fun bits(vararg seqs: Int) = BitSet().apply { seqs.forEach { set(it) } }

    /** Advances the clock in 10 ms ticks (the playout thread's) for [ms], collecting what played as "id:seq". */
    private fun run(ms: Long): List<String> {
        val out = ArrayList<String>()
        val end = now + ms
        while (now <= end) {
            while (true) {
                when (val o = q.poll(now)) {
                    null -> break
                    is ClipOutput.Packet -> out += "${o.transmissionId}:${o.seq}"
                    is ClipOutput.Done -> out += "${o.transmissionId}:done"
                }
            }
            now += TICK
        }
        return out
    }

    @Test(timeout = 60_000)
    fun clipsPlayInTheOrderOfTheirClipMessagesPacedOnePerFrame() {
        q.clip("a", 1, BitSet(), now)
        q.clip("b", 1, BitSet(), now)
        // b's frames arrive first; a still plays first
        pkt("b", 0)
        pkt("b", 1)
        pkt("a", 0)
        pkt("a", 1)
        assertEquals(emptyList<String>(), run(ClipQueue.PREBUFFER_MS - TICK))
        assertEquals("one packet per 20 ms", listOf("a:0"), run(TICK))
        assertEquals(listOf("a:1", "a:done", "b:0", "b:1", "b:done"), run(1_000))
        assertTrue(q.isEmpty)
    }

    @Test(timeout = 60_000)
    fun inClipModeALiveTransmissionPlaysWholeOnceReleased() {
        q.holdLive = true
        assertTrue("held though nothing waits", live("t", 0))
        for (seq in 1..3) assertTrue(live("t", seq))
        assertEquals("nothing before its release", emptyList<String>(), run(2_000))
        q.liveEnded("t")
        assertEquals(listOf("t:0", "t:1", "t:2", "t:3", "t:done"), run(1_000))
        q.holdLive = false
        assertFalse("out of clip mode a new one plays live", live("u", 0))
    }

    @Test(timeout = 60_000)
    fun inClipModeAStalledLiveTransmissionPlaysAfterThreeSeconds() {
        q.holdLive = true
        assertTrue(live("t", 0))
        assertTrue(live("t", 1))
        assertEquals(emptyList<String>(), run(ClipQueue.FINISH_AFTER_MS - TICK))
        assertEquals(listOf("t:0", "t:1", "t:done"), run(1_000))
    }

    @Test(timeout = 60_000)
    fun inClipModeAHeldTransmissionThatIsClippedPlaysAsAClip() {
        q.holdLive = true
        assertTrue(live("t", 0))
        q.clip("t", 2, bits(), now)
        pkt("t", 1)
        pkt("t", 2)
        assertEquals(listOf("t:0", "t:1", "t:2", "t:done"), run(1_000))
    }

    @Test(timeout = 60_000)
    fun liveAudioArrivingWhileAClipWaitsIsHeldAndPlaysAfterIt() {
        assertFalse("nothing waits: plays live", live("live0", 0))
        q.clip("c", 0, BitSet(), now)
        assertTrue(live("live1", 0))
        q.liveEnded("live1")
        assertTrue(live("live2", 0))
        assertTrue(live("live2", 1))
        q.liveEnded("live2")
        pkt("c", 0)
        assertEquals(
            listOf("c:0", "c:done", "live1:0", "live1:done", "live2:0", "live2:1", "live2:done"),
            run(1_000)
        )
        assertFalse("live0 was never held", pkt("live0", 1))
    }

    @Test(timeout = 60_000)
    fun aClipArrivingBetweenAGrantAndItsFirstFrameGoesFirst() {
        q.clip("t1", 0, BitSet(), now)
        // t3 was granted (snapshot) before the clip of t2, which was granted earlier: t3's frames come after t2's
        q.clip("t2", 0, BitSet(), now)
        pkt("t1", 0)
        pkt("t2", 0)
        assertTrue(live("t3", 0))
        q.liveEnded("t3")
        assertEquals(listOf("t1:0", "t1:done", "t2:0", "t2:done", "t3:0", "t3:done"), run(1_000))
    }

    @Test(timeout = 60_000)
    fun aClipWaitsForAMissingSeqUntilFinishedByThreeSecondsWithoutAFrameThenSkipsIt() {
        q.clip("a", 3, BitSet(), now)
        pkt("a", 0)
        pkt("a", 2)
        assertEquals(listOf("a:0"), run(1_000))
        // seq 1 never comes: 3 s after the last frame the clip is finished and the player moves on
        assertEquals(emptyList<String>(), run(ClipQueue.FINISH_AFTER_MS - 1_000 - 2 * TICK))
        assertEquals(listOf("a:2", "a:done"), run(ClipQueue.PREBUFFER_MS + 4 * TICK))
    }

    @Test(timeout = 60_000)
    fun theNextClipFinishesTheOneBeforeButLiveAudioDoesNot() {
        q.clip("a", 3, BitSet(), now)
        pkt("a", 0)
        pkt("a", 2)
        assertEquals(listOf("a:0"), run(200))
        assertTrue(live("live", 0))
        assertEquals("live audio does not finish the clip", emptyList<String>(), run(500))
        q.clip("b", 0, BitSet(), now)
        pkt("b", 0)
        assertEquals(listOf("a:2", "a:done", "live:0", "live:done", "b:0", "b:done"), run(1_000))
    }

    @Test(timeout = 60_000)
    fun framesArrivingAfterFinishPlayIfThePlayerHasNotPassedThem() {
        q.clip("a", 4, BitSet(), now)
        q.clip("b", 0, BitSet(), now)
        pkt("a", 0)
        pkt("a", 2)
        pkt("b", 0)
        // a is finished (b's clip arrived): after seq 0 the player skips the missing seq 1
        assertEquals(listOf("a:0"), run(ClipQueue.PREBUFFER_MS))
        pkt("a", 1)
        pkt("a", 3)
        assertEquals(listOf("a:2", "a:3", "a:done", "b:0", "b:done"), run(1_000))
        assertTrue("a late frame of a played-out clip is taken and dropped", pkt("a", 4))
        assertEquals(emptyList<String>(), run(200))
    }

    @Test(timeout = 60_000)
    fun nothingPlaysWhileThisPhoneIsTalkingAndIncomingLiveWaits() {
        q.busy(true)
        assertTrue("held while talking", live("live", 0))
        q.liveEnded("live")
        q.clip("c", 0, BitSet(), now)
        pkt("c", 0)
        assertEquals(emptyList<String>(), run(5_000))
        q.busy(false)
        assertEquals(listOf("live:0", "live:done", "c:0", "c:done"), run(500))
    }

    @Test(timeout = 60_000)
    fun seqsAcceptedBeforeTheClipArePassedOver() {
        q.clip("a", 20, bits(*IntArray(10) { it }), now)
        pkt("a", 10)
        assertEquals(listOf("a:10"), run(ClipQueue.PREBUFFER_MS).take(1))
    }

    @Test(timeout = 60_000)
    fun aShortTailOfAPartlyHeardTransmissionIsNotPlayed() {
        // seqs 0..9 accepted live, last_seq 18: 9 missing
        q.clip("tail", 18, bits(*IntArray(10) { it }), now)
        assertTrue("its frames are taken and dropped", pkt("tail", 10))
        assertTrue(q.isEmpty)
        // 10 missing plays
        q.clip("longer", 19, bits(*IntArray(10) { it }), now)
        assertFalse(q.isEmpty)
        // a clip of an id with nothing accepted always plays, however short
        q.clip("blip", 0, BitSet(), now)
        pkt("longer", 10)
        pkt("blip", 0)
        assertEquals(listOf("longer:10", "longer:done", "blip:0", "blip:done"), run(1_000))
    }

    @Test(timeout = 60_000)
    fun aClipOfAHeldLiveTransmissionContinuesItsEntry() {
        q.clip("c", 0, BitSet(), now)
        live("t", 0)
        live("t", 1)
        q.liveEnded("t")
        // gapped: the rest comes as a clip; the held packets were accepted, yet they have not played
        q.clip("t", 3, bits(0, 1), now)
        pkt("t", 2)
        pkt("t", 3)
        pkt("c", 0)
        assertEquals(listOf("c:0", "c:done", "t:0", "t:1", "t:2", "t:3", "t:done"), run(1_000))
    }

    private companion object {
        const val TICK = 10L
    }
}
