package com.example.ptt.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstTalkerRuleTest {
    private val window = FirstTalkerRule.WINDOW_MS

    @Test
    fun theSenderStartsLockedAndStaysLockedUntilThePeerIsSeen() {
        val rule = FirstTalkerRule()
        assertTrue(rule.isLocked(0))
        assertTrue(rule.isLocked(10 * window))
        assertNull(rule.deadline())
    }

    @Test
    fun silenceForTenSecondsAfterThePeerJoinsUnlocks() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(1_000)
        assertEquals(1_000 + window, rule.deadline())
        assertTrue(rule.isLocked(1_000 + window - 1))
        assertFalse(rule.isLocked(1_000 + window))
    }

    @Test
    fun theEndOfTheReceiversFirstTransmissionUnlocksAtOnce() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerStartedTalking(0)
        rule.onPeerStoppedTalking()
        assertFalse(rule.isLocked(1))
        assertNull(rule.deadline())
    }

    @Test
    fun aTransmissionCutShortBeforeThePeerWasSeenAgainStillUnlocks() {
        // The socket dropped mid-transmission: the peer is gone from the state, but their turn is over.
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerStartedTalking(1_000)
        rule.onPeerStoppedTalking()
        rule.onPeerPresent(5 * window)
        assertFalse(rule.isLocked(5 * window))
    }

    @Test
    fun aPeerTalkingInsideTheWindowStopsTheClockUntilTheyRelease() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerStartedTalking(0)
        assertNull(rule.deadline())
        assertTrue(rule.isLocked(5 * window))
        rule.onPeerStoppedTalking()
        assertFalse(rule.isLocked(5 * window))
    }

    @Test
    fun aReplayedPeerJoinedAfterAReconnectDoesNotRestartTheWindow() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerPresent(window - 1_000)
        assertEquals(window, rule.deadline())
        assertFalse(rule.isLocked(window))
    }

    @Test
    fun onceUnlockedItStaysUnlockedThroughReconnectsAndRejoins() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        assertFalse(rule.isLocked(window))
        rule.onPeerPresent(window + 1)
        rule.onPeerStartedTalking(window + 1)
        assertFalse(rule.isLocked(window + 2))
        assertNull(rule.deadline())
    }
}
