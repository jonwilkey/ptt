package com.example.ptt.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Section 13 of docs/protocol.md against the shared vectors (`clip_fallback`), receiver side. The `resume`
 * vectors (the talker's resend) are in [com.example.ptt.talk.TalkerTransmissionTest].
 */
class ClipVectorsTest {
    private fun section(name: String): List<JsonValue.Obj> =
        VectorFile.root.obj("clip_fallback").array(name).map { it.asObject(name) }

    private fun b64(v: JsonValue.Obj, key: String) = VectorFile.b64(v, key)

    @Test(timeout = 60_000)
    fun transmissionFramesOpenAsAClip() {
        val vectors = section("transmissions")
        assertEquals(1, vectors.size)
        for (v in vectors) {
            val sid = v.string("session_id")
            val talker = v.string("talker_device_id")
            val tid = v.string("transmission_id")
            assertArrayEquals(b64(v, "info"), FrameCipher.info(sid, talker, tid))
            assertArrayEquals(
                b64(v, "transmission_key"),
                FrameCipher.deriveKey(b64(v, "session_key"), sid, talker, tid)
            )
            val frames = v.array("frames").map { it.asObject("frame") }
            val rx = FrameReceiver(b64(v, "session_key"), sid, talker)
            assertTrue(rx.onClip(talker, tid, frames.size - 1) is ClipVerdict.Accepted)
            for (f in frames) {
                val got = rx.onFrame(b64(f, "frame"))
                assertTrue("seq ${f.number("seq")}", got is FrameVerdict.Accepted)
                assertArrayEquals(b64(f, "plaintext"), (got as FrameVerdict.Accepted).opus)
                assertArrayEquals(b64(f, "nonce"), FrameCipher.nonce(f.number("seq").toInt()))
            }
            assertEquals(frames.size - 1, rx.heardValue(tid))
        }
    }

    @Test(timeout = 60_000)
    fun sessionVectors() {
        val vectors = section("sessions")
        assertEquals(15, vectors.size)
        for (v in vectors) {
            val name = v.string("name")
            val steps = v.array("steps").map { it.asObject("step") }
            val run = if ("receiver" in v.members) PhoneRun(v) else AnonymousRun(v)
            for ((i, step) in steps.withIndex()) run.step(step, "$name step $i")
        }
    }

    private fun frameVerdict(v: FrameVerdict) = when (v) {
        is FrameVerdict.Accepted -> "ok"
        FrameVerdict.Replay -> "replay"
        is FrameVerdict.Dropped -> "drop"
    }

    private fun accept(ok: Boolean) = if (ok) "accept" else "refuse"

    private interface Run {
        fun step(step: JsonValue.Obj, where: String)
    }

    /** A session with a `receiver`: the phone's own [AudioSession], in its role. */
    private inner class PhoneRun(v: JsonValue.Obj) : Run {
        private val receiver = v.obj("receiver")
        private val me = receiver.string("device_id")
        private var nextId = ""
        private val session = when (receiver.string("role")) {
            "answerer" -> AudioSession.forAnswerer(
                b64(v, "session_key"),
                v.string("session_id"),
                me,
                receiver.string("from_device")
            ) { nextId }

            "sender" -> AudioSession.forSender(
                b64(v, "session_key"),
                v.string("session_id"),
                me,
                receiver.array("keys").map { (it as JsonValue.Str).value }.toSet()
            ) { nextId }

            else -> error("unknown role")
        }

        override fun step(step: JsonValue.Obj, where: String) {
            when (step.string("op")) {
                "released" -> session.onFloorReleased(step.string("transmission_id"))
                "reconnected" -> session.onSocketClosed()
                "heard" -> heard(step, session.heardValue(step.string("transmission_id")), where)
                else -> assertEquals(where, step.string("expect"), verdict(step, where))
            }
        }

        private fun verdict(step: JsonValue.Obj, where: String): String = when (step.string("op")) {
            "granted" -> accept(granted(step.string("talker"), step.string("transmission_id")))

            "frame" -> frameVerdict(session.onFrame(b64(step, "frame")))

            "clip" -> accept(
                session.onClip(
                    step.string("talker"),
                    step.string("transmission_id"),
                    step.number("last_seq").toInt()
                ) is ClipVerdict.Accepted
            )

            "peer_joined" -> accept(session.onPeerJoined(step.string("device_id")))

            else -> throw AssertionError("$where: unknown op")
        }

        /** A grant naming this phone answers a request it made for exactly that id. */
        private fun granted(talker: String, id: String): Boolean {
            if (talker != me) return session.onFloorGranted(talker, id) === GrantOutcome.Listen
            nextId = id
            session.requestFloor()
            return session.onFloorGranted(talker, id) is GrantOutcome.Transmit
        }
    }

    /** A session without `receiver` (as in `audio_frames`): the expected peer follows each grant. */
    private inner class AnonymousRun(v: JsonValue.Obj) : Run {
        private val rx = FrameReceiver(b64(v, "session_key"), v.string("session_id"), null)

        override fun step(step: JsonValue.Obj, where: String) {
            when (step.string("op")) {
                "granted" -> {
                    rx.setExpectedPeer(step.string("talker"))
                    val got = rx.onGranted(step.string("talker"), step.string("transmission_id"))
                    assertEquals(
                        where,
                        step.string("expect"),
                        accept(got == GrantVerdict.ACCEPTED || got == GrantVerdict.RESUMED)
                    )
                }

                "released" -> rx.onReleased(step.string("transmission_id"))

                "reconnected" -> rx.onSocketClosed()

                "frame" -> assertEquals(where, step.string("expect"), frameVerdict(rx.onFrame(b64(step, "frame"))))

                "clip" -> assertEquals(
                    where,
                    step.string("expect"),
                    accept(
                        rx.onClip(
                            step.string("talker"),
                            step.string("transmission_id"),
                            step.number("last_seq").toInt()
                        ) is ClipVerdict.Accepted
                    )
                )

                "heard" -> heard(step, rx.heardValue(step.string("transmission_id")), where)

                else -> fail("$where: unknown op")
            }
        }
    }

    private fun heard(step: JsonValue.Obj, value: Int?, where: String) {
        assertEquals(where, step.string("expect"), if (value == null) "none" else "send")
        if (value != null) assertEquals(where, step.number("last_seq").toInt(), value)
    }
}
