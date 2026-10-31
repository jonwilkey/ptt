package com.example.ptt.net

import com.example.ptt.crypto.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ID = "11111111-1111-4111-8111-111111111111"
private const val ID2 = "22222222-2222-4222-8222-222222222222"

class TalkCodecTest {
    @Test(timeout = 60_000)
    fun encodesExactlyTheKeysOfEachMessage() {
        assertEquals(
            """{"type":"hello","protocol":1,"token":"$WS_TOKEN","app_version":"0.3.0"}""",
            TalkCodec.encode(ClientMessage.Hello(1, WS_TOKEN, "0.3.0"))
        )
        assertEquals("""{"type":"join","session_id":"$ID"}""", TalkCodec.encode(ClientMessage.Join(ID)))
        assertEquals(
            """{"type":"floor_request","transmission_id":"$ID"}""",
            TalkCodec.encode(ClientMessage.FloorRequest(ID))
        )
        assertEquals(
            """{"type":"floor_release","transmission_id":"$ID"}""",
            TalkCodec.encode(ClientMessage.FloorRelease(ID))
        )
        assertEquals("""{"type":"session_end"}""", TalkCodec.encode(ClientMessage.SessionEnd))
        assertEquals("""{"type":"ping","t":123}""", TalkCodec.encode(ClientMessage.Ping(123)))
    }

    @Test(timeout = 60_000)
    fun encoderRefusesWhatTheServerWouldRefuse() {
        assertThrows(ProtocolException::class.java) { TalkCodec.encode(ClientMessage.Join("not-a-uuid")) }
        assertThrows(ProtocolException::class.java) { TalkCodec.encode(ClientMessage.Join(OWN.uppercase())) }
        assertThrows(ProtocolException::class.java) { TalkCodec.encode(ClientMessage.FloorRequest("x")) }
        assertThrows(ProtocolException::class.java) { TalkCodec.encode(ClientMessage.Hello(1, "short", "1")) }
        assertThrows(ProtocolException::class.java) { TalkCodec.encode(ClientMessage.Hello(0, WS_TOKEN, "1")) }
        assertThrows(ProtocolException::class.java) { TalkCodec.encode(ClientMessage.Ping(-1)) }
        assertThrows(ProtocolException::class.java) {
            TalkCodec.encode(ClientMessage.Hello(1, WS_TOKEN, "v".repeat(2000)))
        }
    }

    @Test(timeout = 60_000)
    fun tokenNeverAppearsInToString() {
        assertTrue(WS_TOKEN !in ClientMessage.Hello(1, WS_TOKEN, "1").toString())
    }

    @Test(timeout = 60_000)
    fun decodesEveryServerMessage() {
        assertEquals(
            ServerMessage.Hello(1, ID, 5),
            TalkCodec.decode("""{"type":"hello","protocol":1,"device_id":"$ID","server_time":5}""")
        )
        assertEquals(
            ServerMessage.PageState(ID, WsPageState.ACKNOWLEDGED),
            TalkCodec.decode("""{"type":"page_state","page_id":"$ID","state":"acknowledged"}""")
        )
        assertEquals(ServerMessage.PeerJoined(ID), TalkCodec.decode("""{"type":"peer_joined","device_id":"$ID"}"""))
        assertEquals(ServerMessage.PeerLeft(ID), TalkCodec.decode("""{"type":"peer_left","device_id":"$ID"}"""))
        assertEquals(
            ServerMessage.FloorGranted(ID, ID2),
            TalkCodec.decode("""{"type":"floor_granted","talker":"$ID","transmission_id":"$ID2"}""")
        )
        assertEquals(ServerMessage.FloorBusy, TalkCodec.decode("""{"type":"floor_busy"}"""))
        assertEquals(
            ServerMessage.FloorReleased(ID, ID2, ReleaseReason.SILENCE),
            TalkCodec.decode("""{"type":"floor_released","talker":"$ID","transmission_id":"$ID2","reason":"silence"}""")
        )
        assertEquals(
            ServerMessage.SessionEnd(SessionEndReason.IDLE),
            TalkCodec.decode("""{"type":"session_end","reason":"idle"}""")
        )
        assertEquals(
            ServerMessage.Error(WsErrorCode.NO_PEER),
            TalkCodec.decode("""{"type":"error","code":"no_peer"}""")
        )
        assertEquals(ServerMessage.Pong(7, 9), TalkCodec.decode("""{"type":"pong","t":7,"server_time":9}"""))
    }

    @Test(timeout = 60_000)
    fun ignoresUnknownTypesAndFields() {
        assertNull(TalkCodec.decode("""{"type":"clip","transmission_id":"$ID"}"""))
        assertNull(TalkCodec.decode("""{"type":"from_the_future"}"""))
        assertEquals(
            ServerMessage.PeerJoined(ID),
            TalkCodec.decode("""{"type":"peer_joined","device_id":"$ID","extra":{"a":1},"more":[1]}""")
        )
        assertEquals(ServerMessage.FloorBusy, TalkCodec.decode("""{"type":"floor_busy","why":"because"}"""))
    }

    @Test(timeout = 60_000)
    fun unknownEnumValuesTakeTheirFallbacks() {
        assertEquals(
            ServerMessage.SessionEnd(SessionEndReason.ENDED_BY_PEER),
            TalkCodec.decode("""{"type":"session_end","reason":"solar_flare"}""")
        )
        assertEquals(
            ServerMessage.FloorReleased(ID, ID2, ReleaseReason.RELEASED),
            TalkCodec.decode("""{"type":"floor_released","talker":"$ID","transmission_id":"$ID2","reason":"new"}""")
        )
        assertEquals(
            ServerMessage.Error(WsErrorCode.UNKNOWN),
            TalkCodec.decode("""{"type":"error","code":"new_code"}""")
        )
        assertEquals(
            ServerMessage.PageState(ID, WsPageState.UNKNOWN),
            TalkCodec.decode("""{"type":"page_state","page_id":"$ID","state":"teleported"}""")
        )
    }

    @Test(timeout = 60_000)
    fun malformedMessagesAreDroppedNotThrown() {
        assertNull(TalkCodec.decode("not json"))
        assertNull(TalkCodec.decode("[1,2]"))
        assertNull(TalkCodec.decode("""{"type":"peer_joined"}"""))
        assertNull(TalkCodec.decode("""{"type":"peer_joined","device_id":"NOT-A-UUID"}"""))
        assertNull(TalkCodec.decode("""{"type":"pong","t":"7","server_time":9}"""))
        assertNull(TalkCodec.decode("""{"type":"pong","t":-1,"server_time":9}"""))
        assertNull(TalkCodec.decode("""{"type":3}"""))
    }

    @Test(timeout = 60_000)
    fun everyCloseCodeOfTheTableIsMapped() {
        val expected = mapOf(
            1000 to CloseReaction.RECONNECT,
            4400 to CloseReaction.RECONNECT,
            4401 to CloseReaction.UNAUTHORIZED,
            4403 to CloseReaction.UNAUTHORIZED,
            4408 to CloseReaction.RECONNECT,
            4409 to CloseReaction.REPLACED,
            4413 to CloseReaction.RECONNECT,
            4429 to CloseReaction.RECONNECT,
            4500 to CloseReaction.RECONNECT,
            4503 to CloseReaction.RECONNECT,
            1006 to CloseReaction.RECONNECT
        )
        expected.forEach { (code, reaction) -> assertEquals("code $code", reaction, CloseReaction.of(code, null)) }
        assertEquals(CloseReaction.UNAVAILABLE, CloseReaction.of(null, 404))
        assertEquals(CloseReaction.RECONNECT, CloseReaction.of(null, 503))
        assertEquals(CloseReaction.RECONNECT, CloseReaction.of(null, null))
    }
}
