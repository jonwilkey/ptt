package com.example.ptt.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol-2 receiver rules (docs/protocol.md sections 13.5, 13.7, 13.8) through [AudioSession]. */
class ClipReceiverTest {
    private val sid = "a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60"
    private val me = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57"
    private val peer = "7b2d0e94-a1c8-4f53-9d16-b5e8a3c47f20"
    private val sibling = "a4f6b830-9e21-4c7d-b5a8-1f0d6e2c8b93"
    private val stranger = "0d58c2f7-3a9e-4e41-8b7c-6a1f9d4e3b25"
    private val key = ByteArray(32) { (it * 3 + 1).toByte() }

    /** The peer's side: seals the frames of one transmission of [talker]'s. */
    private inner class Talker(talker: String = peer) {
        private val phone = AudioSession.forAnswerer(key, sid, talker, me)
        val id = phone.requestFloor()
        private val sealer = (phone.onFloorGranted(talker, id) as GrantOutcome.Transmit).sealer
        val frames = ArrayList<ByteArray>()

        fun frame(seq: Int): ByteArray {
            while (frames.size <= seq) frames += sealer.seal(byteArrayOf(frames.size.toByte(), 1, 2))
            return frames[seq]
        }
    }

    private fun answerer() = AudioSession.forAnswerer(key, sid, me, peer)

    private fun AudioSession.accepted(frame: ByteArray) = onFrame(frame) as FrameVerdict.Accepted

    private fun AudioSession.clip(t: Talker, last: Int, talker: String = this@ClipReceiverTest.peer) =
        onClip(talker, t.id, last)

    @Test(timeout = 60_000)
    fun clipOfAnUnseenTransmissionIsAcceptedAndPlaysUpToItsLastSeq() {
        val s = answerer()
        val t = Talker()
        val v = s.clip(t, 2) as ClipVerdict.Accepted
        assertFalse(v.endedCurrent)
        assertTrue(v.acceptedBefore.isEmpty)
        assertNull(v.heard)
        for (seq in 0..2) assertTrue(s.onFrame(t.frame(seq)) is FrameVerdict.Accepted)
        val past = s.onFrame(t.frame(3)) as FrameVerdict.Dropped
        assertEquals(FrameDropReason.NO_TRANSMISSION, past.reason)
        // a clip never revives a grant
        assertTrue(s.onFloorGranted(peer, t.id) === GrantOutcome.Ignored)
    }

    @Test(timeout = 60_000)
    fun clipOfTheCurrentTransmissionReleasesItAndKeepsItsAcceptedSet() {
        val s = answerer()
        val t = Talker()
        assertTrue(s.onFloorGranted(peer, t.id) === GrantOutcome.Listen)
        s.accepted(t.frame(0))
        val v = s.clip(t, 3) as ClipVerdict.Accepted
        assertTrue(v.endedCurrent)
        assertTrue(v.acceptedBefore[0] && v.acceptedBefore.cardinality() == 1)
        assertEquals("trigger 1: a clip ending a granted transmission counts as its release", 0, v.heard)
        assertTrue("live seq again from the clip is a replay", s.onFrame(t.frame(0)) === FrameVerdict.Replay)
        s.accepted(t.frame(1))
        // the floor is free: a new grant of the peer's is accepted
        val next = Talker()
        assertTrue(s.onFloorGranted(peer, next.id) === GrantOutcome.Listen)
        // and clip frames still pass alongside the new live transmission
        s.accepted(t.frame(2))
        s.accepted(next.frame(0))
    }

    @Test(timeout = 60_000)
    fun clipOfASuspendedTransmissionReleasesIt() {
        val s = answerer()
        val t = Talker()
        s.onFloorGranted(peer, t.id)
        s.accepted(t.frame(0))
        s.onSocketClosed()
        assertEquals(FrameDropReason.SUSPENDED, (s.onFrame(t.frame(1)) as FrameVerdict.Dropped).reason)
        assertTrue((s.clip(t, 1) as ClipVerdict.Accepted).endedCurrent)
        s.accepted(t.frame(1))
        assertTrue("a released id is refused forever", s.onFloorGranted(peer, t.id) === GrantOutcome.Ignored)
    }

    @Test(timeout = 60_000)
    fun clipNamingThisPhoneOrAnotherTalkerForASeenIdIsRefused() {
        val s = answerer()
        val t = Talker()
        assertTrue(s.clip(t, 1, talker = me) === ClipVerdict.Refused)
        assertTrue(s.onFrame(t.frame(0)) is FrameVerdict.Dropped)
        // the phone's own transmission, then a clip claiming the peer talked under that id
        val own = s.requestFloor()
        assertTrue(s.onFloorGranted(me, own) is GrantOutcome.Transmit)
        s.onFloorReleased(own)
        assertTrue(s.onClip(peer, own, 1) === ClipVerdict.Refused)
        assertNull(s.heardValue(own))
    }

    @Test(timeout = 60_000)
    fun aRefusedClipChangesNothing() {
        val s = answerer()
        val t = Talker()
        s.onFloorGranted(peer, t.id)
        assertTrue(s.clip(t, 1, talker = stranger) === ClipVerdict.Refused)
        // still current: frames pass as live, and the grant is still the current one
        s.accepted(t.frame(0))
        assertTrue(s.onFloorGranted(peer, t.id) === GrantOutcome.Listen)
    }

    @Test(timeout = 60_000)
    fun answererAcceptsOnlyTheEnvelopeSender() {
        val s = answerer()
        assertTrue(s.clip(Talker(sibling), 0, talker = sibling) === ClipVerdict.Refused)
        assertTrue(s.clip(Talker(), 0) is ClipVerdict.Accepted)
    }

    @Test(timeout = 60_000)
    fun senderTakesItsPeerFromTheFirstAcceptedClipFromKeys() {
        val s = AudioSession.forSender(key, sid, me, setOf(peer, sibling))
        assertTrue(s.clip(Talker(stranger), 0, talker = stranger) === ClipVerdict.Refused)
        assertNull(s.peer)
        assertTrue(s.clip(Talker(sibling), 0, talker = sibling) is ClipVerdict.Accepted)
        assertEquals(sibling, s.peer)
        assertTrue(s.clip(Talker(), 0) === ClipVerdict.Refused)
        assertFalse(s.onPeerJoined(peer))
        assertTrue(s.onPeerJoined(sibling))
        assertTrue(s.onFloorGranted(sibling, Talker(sibling).id) === GrantOutcome.Listen)
    }

    @Test(timeout = 60_000)
    fun senderPeerFromPeerJoinedLimitsClips() {
        val s = AudioSession.forSender(key, sid, me, setOf(peer, sibling))
        assertTrue(s.onPeerJoined(peer))
        assertTrue(s.clip(Talker(sibling), 0, talker = sibling) === ClipVerdict.Refused)
        assertTrue(s.clip(Talker(), 0) is ClipVerdict.Accepted)
    }

    @Test(timeout = 60_000)
    fun aResealedFrameIsAReplayAndNeverPlays() {
        val s = answerer()
        val t = Talker()
        s.clip(t, 1)
        s.accepted(t.frame(0))
        val resealed = AudioFrame.encodeHeader(t.id, 0).let { header ->
            header + FrameCipher.seal(FrameCipher.deriveKey(key, sid, peer, t.id), 0, header, byteArrayOf(9, 9, 9))
        }
        assertTrue(s.onFrame(resealed) === FrameVerdict.Replay)
    }

    @Test(timeout = 60_000)
    fun ownTransmissionHoldsTheFloorAndItsFramesAreNeverPlayed() {
        val s = answerer()
        val own = s.requestFloor()
        val sealer = (s.onFloorGranted(me, own) as GrantOutcome.Transmit).sealer
        assertTrue(s.onFrame(sealer.seal(byteArrayOf(1))) is FrameVerdict.Dropped)
        assertTrue(
            "a peer grant while this phone holds the floor",
            s.onFloorGranted(peer, Talker().id) === GrantOutcome.Ignored
        )
        val clip = Talker()
        assertTrue(s.clip(clip, 0) is ClipVerdict.Accepted)
        s.accepted(clip.frame(0))
        s.onFloorReleased(own)
        assertTrue(s.onFloorGranted(peer, Talker().id) === GrantOutcome.Listen)
    }

    @Test(timeout = 60_000)
    fun aGrantToThisPhoneWhileThePeerHoldsTheFloorIsGivenBack() {
        val s = answerer()
        s.onFloorGranted(peer, Talker().id)
        val own = s.requestFloor()
        val out = s.onFloorGranted(me, own)
        assertTrue(out is GrantOutcome.SendRelease && out.transmissionId == own)
    }

    // ---- heard (section 13.5) ----

    @Test(timeout = 60_000)
    fun heardOnReleaseOfAnAcceptedGrantNeverBeforeSeqZero() {
        val s = answerer()
        val t = Talker()
        s.onFloorGranted(peer, t.id)
        assertNull("nothing accepted yet", s.accepted(t.frame(1)).heard)
        assertNull(s.onFloorReleased(t.id))
        val u = Talker()
        s.onFloorGranted(peer, u.id)
        s.accepted(u.frame(0))
        s.accepted(u.frame(2))
        assertEquals("k counts the contiguous prefix", 0, s.onFloorReleased(u.id))
    }

    @Test(timeout = 60_000)
    fun heardWhenTheFrameCompletingAClipArrives() {
        val s = answerer()
        val t = Talker()
        s.clip(t, 2)
        assertNull(s.accepted(t.frame(0)).heard)
        assertNull(s.accepted(t.frame(2)).heard)
        assertEquals(2, s.accepted(t.frame(1)).heard)
        assertNull("no release trigger for a clip-only id", s.onFloorReleased(t.id))
    }

    @Test(timeout = 60_000)
    fun aClipWhoseFramesWereAllAcceptedOnAnEarlierSocketIsHeardAtOnce() {
        val s = answerer()
        val t = Talker()
        s.clip(t, 2)
        for (seq in 0..2) s.accepted(t.frame(seq))
        s.onSocketClosed()
        assertEquals("the earlier heard may have been lost", 2, (s.clip(t, 2) as ClipVerdict.Accepted).heard)

        val u = Talker()
        s.onFloorGranted(peer, u.id)
        for (seq in 0..3) s.accepted(u.frame(seq))
        assertEquals(3, s.onFloorReleased(u.id))
        s.onSocketClosed()
        assertEquals(3, (s.clip(u, 3) as ClipVerdict.Accepted).heard)
    }

    @Test(timeout = 60_000)
    fun heardEveryTwoHundredFiftyFramesAndNeverDecreasing() {
        val s = answerer()
        val t = Talker()
        s.onFloorGranted(peer, t.id)
        val sent = (0..600).mapNotNull { s.accepted(t.frame(it)).heard }
        assertEquals(listOf(249, 499), sent)
        assertEquals(600, s.onFloorReleased(t.id))
    }

    @Test(timeout = 60_000)
    fun aFrameFillingAHoleCanJumpKAndReportsOnce() {
        val s = answerer()
        val t = Talker()
        s.onFloorGranted(peer, t.id)
        for (seq in 1..300) assertNull(s.accepted(t.frame(seq)).heard)
        assertEquals(300, s.accepted(t.frame(0)).heard)
        assertNull(s.accepted(t.frame(301)).heard)
    }

    @Test(timeout = 60_000)
    fun neverHeardForOwnTransmission() {
        val s = answerer()
        val own = s.requestFloor()
        s.onFloorGranted(me, own)
        assertNull(s.onFloorReleased(own))
        assertNull(s.heardValue(own))
    }
}
