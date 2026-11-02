package com.example.ptt.talk

import com.example.ptt.crypto.AudioSession
import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.FrameSealer
import com.example.ptt.crypto.GrantOutcome
import com.example.ptt.crypto.JsonValue
import com.example.ptt.crypto.VectorFile
import com.example.ptt.crypto.array
import com.example.ptt.crypto.asObject
import com.example.ptt.crypto.number
import com.example.ptt.crypto.obj
import com.example.ptt.crypto.string
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TID = "11111111-1111-4111-8111-111111111111"

class TalkerTransmissionTest {
    private fun granted(): TalkerTransmission = TalkerTransmission(TID, protocol2 = true).also {
        it.onGranted(FrameSealer(TID, ByteArray(32) { 3 }))
    }

    private fun TalkerTransmission.drain(): List<ByteArray> = buildList {
        while (true) {
            add(nextFrame() ?: break)
            handed()
        }
    }

    private fun clip(): TalkerTransmission = TalkerTransmission(TID, true, FrameSealer(TID, ByteArray(32) { 3 }))

    @Test(timeout = 60_000)
    fun aClipModeTransmissionSealsFromThePressAndHandsNothingBeforeTheGrant() {
        val t = clip()
        assertTrue(t.capturing)
        assertTrue("nothing plays while it records", t.sending)
        repeat(3) { assertTrue(t.seal(ByteArray(20) { 7 })) }
        assertNull(t.nextFrame())
        assertFalse(t.releaseDue)
        t.stopCapture()
        assertTrue("its buffer still waits for the grant", t.sending)
        assertNull(t.nextFrame())
        assertFalse(t.releaseDue)
        t.onGranted(FrameSealer(TID, ByteArray(32) { 9 }))
        assertFalse("the grant does not restart a capture that ended", t.capturing)
        assertEquals(3, t.drain().size)
        assertTrue(t.releaseDue)
        t.onReleaseHanded()
        assertFalse(t.sending)
    }

    @Test(timeout = 60_000)
    fun aClipModeGrantKeepsTheSealerOfThePress() {
        val t = clip()
        assertTrue(t.seal(ByteArray(20) { 7 }))
        t.onGranted(FrameSealer(TID, ByteArray(32) { 9 }))
        assertTrue(t.capturing)
        assertTrue(t.seal(ByteArray(20) { 8 }))
        assertEquals(2, t.drain().size)
        assertEquals(2, t.sealed)
    }

    @Test(timeout = 60_000)
    fun aClipModeRequestLostWithItsSocketIsSentAfterTheResumeFrom() {
        val t = clip()
        repeat(2) { assertTrue(t.seal(ByteArray(20) { 7 })) }
        t.onSocketLost()
        assertTrue(t.capturing)
        assertTrue(t.seal(ByteArray(20) { 7 }))
        assertNull(t.nextFrame())
        t.onResumeFrom(-1)
        assertTrue(t.granted)
        assertEquals(3, t.drain().size)
    }

    @Test(timeout = 60_000)
    fun aResendIsTheSameBytesAndNothingIsSealedAgain() {
        val t = granted()
        repeat(5) { assertTrue(t.seal(ByteArray(20) { i -> (i + it).toByte() })) }
        val first = t.drain()
        assertEquals(5, first.size)
        t.onSocketLost()
        assertNull("nothing goes before the resume_from", t.nextFrame())
        t.onResumeFrom(1)
        val resent = t.drain()
        assertEquals(3, resent.size)
        for (i in 0..2) assertArrayEquals(first[i + 2], resent[i])
        assertEquals("sealed once", 5, t.sealed)
    }

    @Test(timeout = 60_000)
    fun captureStopsAtSeq2999() {
        val t = granted()
        repeat(TalkerTransmission.MAX_FRAMES - 1) { assertTrue(t.seal(ByteArray(1))) }
        assertTrue(t.capturing)
        assertTrue("seq 2999 is sealed", t.seal(ByteArray(1)))
        assertFalse(t.capturing)
        assertFalse("nothing past it", t.seal(ByteArray(1)))
        assertEquals(3000, t.sealed)
    }

    @Test(timeout = 60_000)
    fun theReleaseIsDueOnlyOnceCaptureIsOverAndEveryFrameWasHanded() {
        val t = granted()
        t.seal(ByteArray(1))
        t.seal(ByteArray(1))
        assertFalse(t.releaseDue)
        t.stopCapture()
        assertFalse("frames still unsent", t.releaseDue)
        assertTrue(t.sending)
        t.drain()
        assertTrue(t.releaseDue)
        t.onReleaseHanded()
        assertFalse(t.releaseDue)
        assertFalse("everything handed", t.sending)
        t.onSocketLost()
        assertTrue("the release may not have arrived: resent after the resume", t.sending)
        t.onResumeFrom(1)
        assertTrue(t.releaseDue)
    }

    @Test(timeout = 60_000)
    fun onlyTheFrameJustCapturedIsLiveTheRestIsBacklog() {
        val t = granted()
        t.seal(ByteArray(1))
        assertFalse(t.backlog)
        t.seal(ByteArray(1))
        assertTrue("seq 0 is behind the newest frame", t.backlog)
        t.handed()
        assertFalse(t.backlog)
        t.stopCapture()
        assertTrue("after capture everything left is backlog", t.backlog)
    }

    @Test(timeout = 60_000)
    fun aResumeFromPastWhatWasSealedSendsNothingAndVouchesForEverything() {
        val t = granted()
        repeat(3) { t.seal(ByteArray(1)) }
        t.onSocketLost()
        assertTrue(t.mayLackFrames)
        t.onResumeFrom(10)
        assertTrue(t.drain().isEmpty())
        assertFalse(t.mayLackFrames)
        t.seal(ByteArray(1))
        assertTrue(t.mayLackFrames)
    }

    @Test(timeout = 60_000)
    fun theFloorLossKeepsTheTransmissionOpen() {
        val t = granted()
        t.seal(ByteArray(1))
        t.onFloorLost()
        assertFalse(t.floorHeld)
        assertTrue(t.capturing)
        assertTrue(t.seal(ByteArray(1)))
        assertEquals(2, t.drain().size)
    }

    @Test(timeout = 60_000)
    fun thePacerAllows200FramesInAnyOneSecond() {
        val p = SendPacer()
        var now = 0L
        var sent = 0
        val times = mutableListOf<Long>()
        while (now < 3_000) {
            if (p.waitMs(now) == 0L) {
                p.sent(now)
                times += now
                sent++
            } else {
                now += p.waitMs(now)
            }
        }
        for (i in times.indices) {
            val inWindow = times.count { it >= times[i] && it < times[i] + 1_000 }
            assertTrue("at most 200 in [${times[i]}, +1 s)", inWindow <= 200)
        }
        assertEquals(600, sent)
        assertEquals(1_000L, times[200])
    }

    @Test(timeout = 60_000)
    fun live50PerSecondIsNeverHeldBack() {
        val p = SendPacer()
        for (i in 0 until 500) {
            assertEquals(0L, p.waitMs(i * 20L))
            p.sent(i * 20L)
        }
    }

    /** The `resume` vectors of `clip_fallback`: given `resume_from`, the talker sends exactly the stored bytes. */
    @Test(timeout = 60_000)
    fun resumeVectors() {
        val section = VectorFile.root.obj("clip_fallback")
        val tx = section.array("transmissions").map { it.asObject("transmission") }.single()
        val frames = tx.array("frames").map { it.asObject("frame") }
        val talker = tx.string("talker_device_id")
        val tid = tx.string("transmission_id")
        val resumes = section.array("resume").map { it.asObject("resume") }
        assertTrue(resumes.isNotEmpty())
        for (v in resumes) {
            assertEquals(tx.string("name"), v.string("transmission"))
            val audio = AudioSession.forSender(
                VectorFile.b64(tx, "session_key"),
                tx.string("session_id"),
                talker,
                setOf("22222222-2222-4222-8222-222222222222")
            ) { tid }
            audio.requestFloor()
            val grant = audio.onFloorGranted(talker, tid) as GrantOutcome.Transmit
            val t = TalkerTransmission(tid, protocol2 = true)
            t.onGranted(grant.sealer)
            for (f in frames) t.seal(VectorFile.b64(f, "plaintext"))
            val live = t.drain()
            for ((i, f) in frames.withIndex()) assertArrayEquals("sealed seq $i", VectorFile.b64(f, "frame"), live[i])
            t.onSocketLost()
            t.onResumeFrom(v.number("resume_from").toInt())
            val expected = v.array("resend").map { (it as JsonValue.Str).value }
            val resent = t.drain().map(B64u::encode)
            assertEquals(v.string("name"), expected, resent)
            audio.close()
        }
    }
}
