package com.example.ptt.crypto

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Section 12 of docs/protocol.md against the shared vectors (`audio_frames`) and behavioural tests. */
class AudioFrameCryptoTest {
    private fun section(name: String): List<JsonValue.Obj> =
        VectorFile.root.obj("audio_frames").array(name).map { it.asObject(name) }

    private fun b64(v: JsonValue.Obj, key: String) = VectorFile.b64(v, key)

    private fun receiver(v: JsonValue.Obj, talker: String) =
        FrameReceiver(b64(v, "session_key"), v.string("session_id"), talker)

    @Test(timeout = 60_000)
    fun transmissionVectors() {
        val vectors = section("transmissions")
        assertEquals(3, vectors.size)
        assertEquals(12, vectors.sumOf { it.array("frames").size })
        for (v in vectors) {
            val name = v.string("name")
            val sessionKey = b64(v, "session_key")
            val sid = v.string("session_id")
            val talker = v.string("talker_device_id")
            val tid = v.string("transmission_id")
            assertArrayEquals(name, b64(v, "info"), FrameCipher.info(sid, talker, tid))
            val key = FrameCipher.deriveKey(sessionKey, sid, talker, tid)
            assertArrayEquals("$name key", b64(v, "transmission_key"), key)
            val rx = receiver(v, talker)
            assertEquals(GrantVerdict.ACCEPTED, rx.onGranted(talker, tid))
            val sealer = FrameSealer(tid, key.copyOf())
            val frames = v.array("frames").map { it.asObject("frame") }
            assertTrue(frames.size >= 3)
            for (f in frames) {
                val seq = f.number("seq").toInt()
                assertArrayEquals(b64(f, "nonce"), FrameCipher.nonce(seq))
                val wire = b64(f, "frame")
                // sealing the vector plaintext reproduces the exact wire frame
                val header = AudioFrame.encodeHeader(tid, seq)
                val sealed = if (seq == sealer.sealedCount) {
                    sealer.seal(b64(f, "plaintext"))
                } else {
                    // the vector jumps ahead (seq 2999, 4095)
                    header + FrameCipher.seal(key, seq, header, b64(f, "plaintext"))
                }
                assertArrayEquals("$name #$seq sealed", wire, sealed)
                // and opening it yields the plaintext
                val verdict = rx.onFrame(wire)
                assertTrue("$name #$seq", verdict is FrameVerdict.Accepted)
                assertArrayEquals("$name #$seq opened", b64(f, "plaintext"), (verdict as FrameVerdict.Accepted).opus)
            }
        }
    }

    @Test(timeout = 60_000)
    fun rejectVectors() {
        val vectors = section("reject")
        assertEquals(21, vectors.size)
        for (v in vectors) {
            val name = v.string("name")
            val rx = receiver(v, v.string("talker_device_id"))
            assertEquals(GrantVerdict.ACCEPTED, rx.onGranted(v.string("talker_device_id"), v.string("transmission_id")))
            val verdict = rx.onFrame(b64(v, "frame"))
            assertTrue("$name: $verdict", verdict is FrameVerdict.Dropped)
            assertEquals(name, v.string("reason"), (verdict as FrameVerdict.Dropped).reason.wireName)
            // a rejected frame uses up no seq: the genuine frame is not harmed (checked in replay vectors)
        }
    }

    @Test(timeout = 60_000)
    fun replayVectors() {
        val vectors = section("replay")
        assertEquals(3, vectors.size)
        for (v in vectors) {
            val name = v.string("name")
            val talker = v.string("talker_device_id")
            val rx = receiver(v, talker)
            assertEquals(name, GrantVerdict.ACCEPTED, rx.onGranted(talker, v.string("transmission_id")))
            val frames = v.array("frames").map { B64u.decode((it as JsonValue.Str).value) }
            val expect = v.array("expect").map { (it as JsonValue.Str).value }
            assertEquals(name, expect.size, frames.size)
            for ((i, frame) in frames.withIndex()) {
                val got = when (rx.onFrame(frame)) {
                    is FrameVerdict.Accepted -> "ok"
                    FrameVerdict.Replay -> "replay"
                    is FrameVerdict.Dropped -> "drop"
                }
                assertEquals("$name #$i", expect[i], got)
            }
        }
    }

    @Test(timeout = 60_000)
    fun sessionVectors() {
        val vectors = section("sessions")
        assertEquals(8, vectors.size)
        for (v in vectors) {
            val name = v.string("name")
            val steps = v.array("steps").map { it.asObject("step") }
            // The vectors have two talkers; the expected peer follows each grant so rule 4 is what is tested.
            val rx = FrameReceiver(b64(v, "session_key"), v.string("session_id"), null)
            for ((i, step) in steps.withIndex()) runStep(rx, step, "$name step $i")
        }
    }

    private fun runStep(rx: FrameReceiver, step: JsonValue.Obj, where: String) {
        when (step.string("op")) {
            "granted" -> {
                rx.setExpectedPeer(step.string("talker"))
                val got = rx.onGranted(step.string("talker"), step.string("transmission_id"))
                val accepted = got == GrantVerdict.ACCEPTED || got == GrantVerdict.RESUMED
                assertEquals(where, step.string("expect"), if (accepted) "accept" else "refuse")
            }

            "released" -> rx.onReleased(step.string("transmission_id"))

            "reconnected" -> rx.onSocketClosed()

            "frame" -> {
                val got = when (rx.onFrame(b64(step, "frame"))) {
                    is FrameVerdict.Accepted -> "ok"
                    FrameVerdict.Replay -> "replay"
                    is FrameVerdict.Dropped -> "drop"
                }
                assertEquals(where, step.string("expect"), got)
            }

            else -> fail("$where: unknown op")
        }
    }

    @Test(timeout = 60_000)
    fun transmissionIdByteOrder() {
        val id = UUID(0x0123456789abcdefL, -0x0123456789abcdf0L)
        val header = AudioFrame.encodeHeader(id.toString(), 0x0ABC)
        assertEquals(21, header.size)
        assertEquals(1, header[0].toInt())
        val msb = java.nio.ByteBuffer.wrap(header, 1, 8).long
        val lsb = java.nio.ByteBuffer.wrap(header, 9, 8).long
        assertEquals(id.mostSignificantBits, msb)
        assertEquals(id.leastSignificantBits, lsb)
        assertNotEquals(msb, lsb)
        assertArrayEquals(byteArrayOf(0, 0, 0x0A, 0xBC.toByte()), header.copyOfRange(17, 21))
        val parsed = (AudioFrame.parse(header + ByteArray(20)) as HeaderResult.Parsed).header
        assertEquals(id.toString(), parsed.transmissionId)
        assertEquals(0x0ABC, parsed.seq)
    }

    @Test(timeout = 60_000)
    fun talkerMintsDistinctV4IdsAndRefusesReuse() {
        val floor = TalkerFloor()
        val ids = (1..200).map { floor.request() }
        assertEquals(200, ids.toSet().size)
        for (id in ids) assertEquals('4', id[14])
        val fixed = TalkerFloor { "11111111-1111-4111-8111-111111111111" }
        fixed.request()
        try {
            fixed.request()
            fail("reuse accepted")
        } catch (_: ProtocolException) {
        }
        try {
            TalkerFloor { "11111111-1111-1111-8111-111111111111" }.request()
            fail("non-v4 accepted")
        } catch (_: ProtocolException) {
        }
    }

    private val sid = "a8d4e6b2-71c9-4f30-8b5e-2c9a1d7e3f60"
    private val me = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57"
    private val peer = "7b2d0e94-a1c8-4f53-9d16-b5e8a3c47f20"
    private val key = ByteArray(32) { it.toByte() }

    private fun session() = AudioSession.forAnswerer(key, sid, me, peer)

    @Test(timeout = 60_000)
    fun grantMustMatchOutstandingRequest() {
        val s = session()
        val unsolicited = "22222222-2222-4222-8222-222222222222"
        val out = s.onFloorGranted(me, unsolicited)
        assertTrue(out is GrantOutcome.SendRelease && out.transmissionId == unsolicited)
        val id = s.requestFloor()
        val wrong = s.onFloorGranted(me, unsolicited)
        assertTrue(wrong is GrantOutcome.SendRelease)
        val ok = s.onFloorGranted(me, id)
        assertTrue(ok is GrantOutcome.Transmit)
        assertTrue(s.onFloorGranted(me, id) === GrantOutcome.Ignored)
        s.onFloorReleased(id)
        // after release the same grant is unsolicited again
        assertTrue(s.onFloorGranted(me, id) is GrantOutcome.SendRelease)
    }

    @Test(timeout = 60_000)
    fun grantAfterButtonReleasedIsReleased() {
        val s = session()
        val id = s.requestFloor()
        assertEquals(null, s.buttonReleased())
        val out = s.onFloorGranted(me, id)
        assertTrue(out is GrantOutcome.SendRelease && out.transmissionId == id)
    }

    @Test(timeout = 60_000)
    fun failedRequestIdIsNeverReusedAndSocketCloseLosesFloor() {
        val s = session()
        val a = s.requestFloor()
        s.onRequestFailed(a)
        assertTrue(s.onFloorGranted(me, a) is GrantOutcome.SendRelease)
        val b = s.requestFloor()
        assertNotEquals(a, b)
        val out = s.onFloorGranted(me, b) as GrantOutcome.Transmit
        out.sealer.seal(ByteArray(10))
        s.onSocketClosed()
        assertTrue(out.sealer.isClosed)
        assertTrue(s.onFloorGranted(me, b) is GrantOutcome.SendRelease)
    }

    @Test(timeout = 60_000)
    fun sealerStopsAtMaxSeqAndNeverReseals() {
        val tid = "33333333-3333-4333-8333-333333333333"
        val sealer = FrameSealer(tid, ByteArray(32) { 7 })
        val seen = HashSet<Int>()
        val pkt = ByteArray(5)
        for (i in 0..AudioFrame.MAX_SEQ) {
            val frame = sealer.seal(pkt)
            val seq = (AudioFrame.parse(frame) as HeaderResult.Parsed).header.seq
            assertEquals(i, seq)
            assertTrue(seen.add(seq))
        }
        assertTrue(sealer.exhausted)
        try {
            sealer.seal(pkt)
            fail("sealed past MaxSeq")
        } catch (_: ProtocolException) {
        }
        assertEquals(AudioFrame.MAX_SEQ + 1, sealer.sealedCount)
        assertArrayEquals(sealer.stored(5), sealer.stored(5))
        try {
            FrameSealer(tid, ByteArray(32)).seal(ByteArray(0))
            fail("empty packet sealed")
        } catch (_: ProtocolException) {
        }
        try {
            FrameSealer(tid, ByteArray(32)).seal(ByteArray(1276))
            fail("oversize packet sealed")
        } catch (_: ProtocolException) {
        }
    }

    @Test(timeout = 60_000)
    fun roundTripSealerToReceiver() {
        val talker = AudioSession.forAnswerer(key, sid, me, peer)
        val listener = AudioSession.forAnswerer(key, sid, peer, me)
        val id = talker.requestFloor()
        val t = talker.onFloorGranted(me, id) as GrantOutcome.Transmit
        assertTrue(listener.onFloorGranted(me, id) === GrantOutcome.Listen)
        val rnd = java.util.Random(1)
        for (i in 0 until 400) {
            val pkt = ByteArray(1 + rnd.nextInt(200)).also { rnd.nextBytes(it) }
            val wire = t.sealer.seal(pkt)
            val v = listener.onFrame(wire)
            assertTrue(v is FrameVerdict.Accepted)
            assertArrayEquals(pkt, (v as FrameVerdict.Accepted).opus)
            if (i % 50 == 0) assertTrue(listener.onFrame(wire) === FrameVerdict.Replay)
        }
        // a talker never hears its own audio: the sender's own session does not accept its own talker
        assertTrue(talker.onFrame(t.sealer.seal(ByteArray(3))) is FrameVerdict.Dropped)
    }

    @Test(timeout = 60_000)
    fun senderOnlyAcceptsPeerFromEnvelopeKeys() {
        val s = AudioSession.forSender(key, sid, me, setOf(peer))
        val stranger = "44444444-4444-4444-8444-444444444444"
        val tid = "55555555-5555-4555-8555-555555555555"
        assertTrue(s.onFloorGranted(peer, tid) === GrantOutcome.Ignored)
        assertFalse(s.onPeerJoined(stranger))
        assertTrue(s.onFloorGranted(stranger, tid) === GrantOutcome.Ignored)
        assertTrue(s.onPeerJoined(peer))
        assertTrue(s.onFloorGranted(peer, tid) === GrantOutcome.Listen)
    }

    @Test(timeout = 60_000)
    fun closeZeroesKeys() {
        val s = session()
        val id = s.requestFloor()
        val t = s.onFloorGranted(me, id) as GrantOutcome.Transmit
        assertFalse(s.keyIsZeroed)
        s.close()
        assertTrue(s.keyIsZeroed)
        assertTrue(t.sealer.isClosed)
        assertTrue(s.onFloorGranted(peer, "66666666-6666-4666-8666-666666666666") === GrantOutcome.Ignored)
    }

    @Test(timeout = 60_000)
    fun answererNeverChangesPeer() {
        val sibling = "77777777-7777-4777-8777-777777777777"
        val s = AudioSession.forAnswerer(key, sid, me, peer)
        assertFalse(s.onPeerJoined(sibling))
        assertTrue(s.onPeerJoined(peer))
        val tid = "55555555-5555-4555-8555-555555555555"
        assertTrue(s.onFloorGranted(sibling, tid) === GrantOutcome.Ignored)
        assertTrue(s.onFloorGranted(peer, tid) === GrantOutcome.Listen)
    }

    @Test(timeout = 60_000)
    fun senderPinsFirstPeerFromKeys() {
        val sibling = "77777777-7777-4777-8777-777777777777"
        val s = AudioSession.forSender(key, sid, me, setOf(peer, sibling, me))
        assertFalse(s.onPeerJoined(me))
        assertTrue(s.onPeerJoined(sibling))
        assertTrue(s.onPeerJoined(sibling))
        assertFalse(s.onPeerJoined(peer))
        val tid = "55555555-5555-4555-8555-555555555555"
        assertTrue(s.onFloorGranted(peer, tid) === GrantOutcome.Ignored)
        assertTrue(s.onFloorGranted(sibling, tid) === GrantOutcome.Listen)
    }

    @Test(timeout = 60_000)
    fun zeroKeyIsRefused() {
        for (make in listOf<() -> Any>(
            { AudioSession.forAnswerer(ByteArray(32), sid, me, peer) },
            { AudioSession.forSender(ByteArray(32), sid, me, setOf(peer)) },
            { FrameReceiver(ByteArray(32), sid, peer) },
            { FrameCipher.deriveKey(ByteArray(32), sid, me, sid) },
            { AudioSession.forAnswerer(ByteArray(31) { 1 }, sid, me, peer) }
        )) {
            try {
                make()
                fail("unusable key accepted")
            } catch (_: ProtocolException) {
            }
        }
    }

    @Test(timeout = 60_000)
    fun suspendedStrangerGrantIsIgnoredThenSamePeerResumes() {
        val talker = AudioSession.forAnswerer(key, sid, me, peer)
        val listener = AudioSession.forAnswerer(key, sid, peer, me)
        val id = talker.requestFloor()
        val t = talker.onFloorGranted(me, id) as GrantOutcome.Transmit
        assertTrue(listener.onFloorGranted(me, id) === GrantOutcome.Listen)
        val f0 = t.sealer.seal(ByteArray(4))
        assertTrue(listener.onFrame(f0) is FrameVerdict.Accepted)
        listener.onSocketClosed()
        assertTrue(listener.onFrame(t.sealer.seal(ByteArray(4))) is FrameVerdict.Dropped)
        val stranger = "88888888-8888-4888-8888-888888888888"
        assertTrue(listener.onFloorGranted(stranger, "99999999-9999-4999-8999-999999999999") === GrantOutcome.Ignored)
        assertTrue(listener.onFloorGranted(me, id) === GrantOutcome.Listen)
        assertTrue(listener.onFrame(f0) === FrameVerdict.Replay)
        assertTrue(listener.onFrame(t.sealer.seal(ByteArray(4))) is FrameVerdict.Accepted)
        // a different transmission from the peer after another suspension releases the old one
        listener.onSocketClosed()
        assertTrue(listener.onFloorGranted(me, "99999999-9999-4999-8999-999999999999") === GrantOutcome.Listen)
        assertTrue(listener.onFloorGranted(me, id) === GrantOutcome.Ignored)
    }

    @Test(timeout = 60_000)
    fun buttonReleasedAfterCloseIsNull() {
        val s = session()
        val id = s.requestFloor()
        s.onFloorGranted(me, id)
        s.close()
        assertEquals(null, s.buttonReleased())
    }

    @Test(timeout = 60_000)
    fun sealerStoresCopies() {
        val sealer = FrameSealer("33333333-3333-4333-8333-333333333333", ByteArray(32) { 7 })
        val frame = sealer.seal(ByteArray(5))
        val before = frame.copyOf()
        frame.fill(0)
        assertArrayEquals(before, sealer.stored(0))
    }
}
