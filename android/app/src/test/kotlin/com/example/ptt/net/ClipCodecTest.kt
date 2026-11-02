package com.example.ptt.net

import com.example.ptt.crypto.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private const val TID = "11111111-1111-4111-8111-111111111111"

/** The protocol-2 listener messages of docs/protocol.md section 13.2. */
class ClipCodecTest {
    @Test(timeout = 60_000)
    fun heardIsEncodedWithExactlyItsKeys() {
        assertEquals(
            """{"type":"heard","transmission_id":"$TID","last_seq":249}""",
            TalkCodec.encode(ClientMessage.Heard(TID, 249))
        )
        assertEquals(
            """{"type":"heard","transmission_id":"$TID","last_seq":4095}""",
            TalkCodec.encode(ClientMessage.Heard(TID, 4095))
        )
        for (bad in listOf(ClientMessage.Heard(TID, -1), ClientMessage.Heard(TID, 4096), ClientMessage.Heard("x", 0))) {
            assertThrows(ProtocolException::class.java) { TalkCodec.encode(bad) }
        }
    }

    @Test(timeout = 60_000)
    fun clipAndListenerGapDecode() {
        assertEquals(
            ServerMessage.Clip(PEER_DEVICE, TID, 2999),
            TalkCodec.decode("""{"type":"clip","talker":"$PEER_DEVICE","transmission_id":"$TID","last_seq":2999}""")
        )
        assertEquals(
            ServerMessage.Clip(PEER_DEVICE, TID, 0),
            TalkCodec.decode("""{"last_seq":0,"transmission_id":"$TID","type":"clip","talker":"$PEER_DEVICE","x":1}""")
        )
        assertEquals(
            ServerMessage.ListenerGap(TID),
            TalkCodec.decode("""{"type":"listener_gap","transmission_id":"$TID"}""")
        )
    }

    @Test(timeout = 60_000)
    fun malformedClipsAreDropped() {
        val bad = listOf(
            """{"type":"clip","talker":"$PEER_DEVICE","transmission_id":"$TID","last_seq":3000}""",
            """{"type":"clip","talker":"$PEER_DEVICE","transmission_id":"$TID","last_seq":-1}""",
            """{"type":"clip","talker":"$PEER_DEVICE","transmission_id":"$TID","last_seq":"1"}""",
            """{"type":"clip","talker":"$PEER_DEVICE","transmission_id":"$TID","last_seq":1.5}""",
            """{"type":"clip","talker":"$PEER_DEVICE","transmission_id":"$TID"}""",
            """{"type":"clip","talker":"${PEER_DEVICE.uppercase()}","transmission_id":"$TID","last_seq":1}""",
            """{"type":"clip","transmission_id":"$TID","last_seq":1}""",
            """{"type":"listener_gap","transmission_id":"nope"}""",
            """{"type":"listener_gap"}"""
        )
        for (text in bad) assertNull(text, TalkCodec.decode(text))
    }
}
