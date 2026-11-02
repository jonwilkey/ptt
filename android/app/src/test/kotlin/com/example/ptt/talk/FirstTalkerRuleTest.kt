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
        rule.onPeerStartedTalking(0, FIRST)
        rule.onPeerStoppedTalking()
        assertFalse(rule.isLocked(1))
        assertNull(rule.deadline())
    }

    @Test
    fun aTransmissionCutShortBeforeThePeerWasSeenAgainStillUnlocks() {
        // Their transmission ended before a rejoin's replayed peer_joined: the turn is over.
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerStartedTalking(1_000, FIRST)
        rule.onPeerStoppedTalking()
        rule.onPeerPresent(5 * window)
        assertFalse(rule.isLocked(5 * window))
    }

    @Test
    fun aPeerTalkingInsideTheWindowStopsTheClockUntilTheyRelease() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerStartedTalking(0, FIRST)
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
        rule.onPeerStartedTalking(window + 1, FIRST)
        assertFalse(rule.isLocked(window + 2))
        assertNull(rule.deadline())
    }

    @Test
    fun aSuspendedTransmissionThatResumesKeepsTheLockUntilItsRelease() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerStartedTalking(1_000, FIRST)
        rule.onOwnSocketLost()
        assertTrue(rule.isLocked(2_000))
        rule.onPeerStartedTalking(3_000, FIRST)
        rule.onRejoinSettled()
        assertTrue("the snapshot resumed it", rule.isLocked(5 * window))
        assertNull(rule.deadline())
        rule.onPeerStoppedTalking()
        assertFalse(rule.isLocked(5 * window))
    }

    @Test
    fun aRejoinThatDoesNotResumeTheSuspendedTransmissionUnlocks() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerStartedTalking(1_000, FIRST)
        rule.onOwnSocketLost()
        rule.onPeerPresent(3_000)
        assertTrue(rule.isLocked(3_000))
        rule.onRejoinSettled()
        assertFalse(rule.isLocked(3_000))
    }

    @Test
    fun aNewTransmissionAfterTheSuspendedOneMeansTheFirstEndedAndUnlocks() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onPeerStartedTalking(1_000, FIRST)
        rule.onOwnSocketLost()
        rule.onPeerStartedTalking(3_000, SECOND)
        assertFalse(rule.isLocked(3_000))
    }

    @Test
    fun aSocketLostAfterTheLockLiftedChangesNothing() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        assertFalse(rule.isLocked(window))
        rule.onPeerStartedTalking(window + 1, SECOND)
        rule.onOwnSocketLost()
        assertFalse(rule.isLocked(window + 2))
        rule.onRejoinSettled()
        assertFalse(rule.isLocked(window + 3))
    }

    @Test
    fun aSocketLostBeforeAnyTransmissionKeepsTheWindowRunning() {
        val rule = FirstTalkerRule()
        rule.onPeerPresent(0)
        rule.onOwnSocketLost()
        rule.onRejoinSettled()
        assertTrue(rule.isLocked(window - 1))
        assertFalse(rule.isLocked(window))
    }

    private companion object {
        const val FIRST = "11111111-1111-4111-8111-111111111111"
        const val SECOND = "22222222-2222-4222-8222-222222222222"
    }
}
