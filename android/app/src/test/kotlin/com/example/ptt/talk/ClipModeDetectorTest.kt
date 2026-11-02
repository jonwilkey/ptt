package com.example.ptt.talk

import com.example.ptt.net.RttSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipModeDetectorTest {
    private val detector = ClipModeDetector()
    private var now = 0L

    /** One sample every 2 s, as the protocol-2 ping makes them. */
    private fun sample(millis: Long, timedOut: Boolean = false): Boolean {
        now += 2_000
        detector.onSample(RttSample(now, millis, timedOut))
        return detector.on
    }

    private fun samples(n: Int, millis: Long) = repeat(n) { sample(millis) }

    private fun reconnectAfter(ms: Long): Boolean {
        now += ms
        detector.onReconnect(now)
        return detector.on
    }

    private fun enter() {
        samples(3, 900)
        assertTrue(detector.on)
    }

    @Test
    fun threeConsecutiveSamplesAboveEightHundredEnter() {
        samples(2, 801)
        assertFalse(detector.on)
        assertTrue(sample(801))
    }

    @Test
    fun exactlyEightHundredIsNotAbove() {
        samples(10, 800)
        assertFalse(detector.on)
    }

    @Test
    fun anyLowerSampleRestartsTheCount() {
        samples(2, 900)
        sample(800)
        samples(2, 900)
        assertFalse(detector.on)
        assertTrue(sample(900))
    }

    @Test
    fun aTimedOutPingCountsAsAboveWhateverItsMillis() {
        sample(800, timedOut = true)
        sample(800, timedOut = true)
        assertTrue(sample(799, timedOut = true))
    }

    @Test
    fun aSecondReconnectWithinThirtySecondsEnters() {
        assertFalse(reconnectAfter(0))
        assertTrue(reconnectAfter(30_000))
    }

    @Test
    fun reconnectsFurtherApartDoNotEnterButTheNextCloseOneDoes() {
        assertFalse(reconnectAfter(0))
        assertFalse(reconnectAfter(30_001))
        assertFalse(reconnectAfter(40_000))
        assertTrue(reconnectAfter(29_000))
    }

    @Test
    fun fifteenSamplesAtOrBelowFourHundredLeave() {
        enter()
        samples(14, 400)
        assertTrue(detector.on)
        assertFalse(sample(400))
    }

    @Test
    fun aSampleAboveFourHundredRestartsTheLeavingCount() {
        enter()
        samples(14, 100)
        sample(401)
        samples(14, 100)
        assertTrue(detector.on)
        assertFalse(sample(100))
    }

    @Test
    fun aTimedOutSampleIsNeverCalm() {
        enter()
        samples(14, 100)
        sample(100, timedOut = true)
        samples(14, 100)
        assertTrue(detector.on)
    }

    @Test
    fun aReconnectInTheLastSixtySecondsKeepsClipMode() {
        enter()
        reconnectAfter(0)
        samples(15, 100)
        // 30 s since the reconnect
        assertTrue(detector.on)
        samples(15, 100)
        // 60 s since the reconnect: still "in the last 60 s"
        assertTrue(detector.on)
        assertFalse("62 s since the reconnect", sample(100))
    }

    @Test
    fun betweenFourAndEightHundredNothingChanges() {
        samples(30, 600)
        assertFalse("never enters", detector.on)
        enter()
        samples(30, 600)
        assertTrue("never leaves", detector.on)
    }

    @Test
    fun aFlappingLinkStaysInClipMode() {
        enter()
        repeat(20) {
            samples(14, 100)
            sample(900)
        }
        assertTrue(detector.on)
    }

    @Test
    fun afterLeavingItTakesThreeHighSamplesAgain() {
        enter()
        samples(15, 100)
        assertFalse(detector.on)
        samples(2, 900)
        assertFalse(detector.on)
        assertTrue(sample(900))
    }
}
