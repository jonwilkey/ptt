package com.example.ptt.net

import com.example.ptt.crypto.Validation
import com.example.ptt.crypto.ensure
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The `hello.protocol` this build speaks (docs/protocol.md section 11.1). */
const val TALK_PROTOCOL = 1

/** Messages the phone sends over the WebSocket (docs/protocol.md section 11.2). The server is strict about them. */
sealed interface ClientMessage {
    class Hello(val protocol: Int, val token: String, val appVersion: String) : ClientMessage {
        // The token is a credential: never printed.
        override fun toString() = "Hello"
    }

    data class Join(val sessionId: String) : ClientMessage

    data class FloorRequest(val transmissionId: String) : ClientMessage

    data class FloorRelease(val transmissionId: String) : ClientMessage

    data object SessionEnd : ClientMessage

    data class Ping(val t: Long) : ClientMessage
}

/** `page_state.state` values of protocol 1; anything else is [UNKNOWN] and means "no change". */
enum class WsPageState(val wire: String) {
    RINGING("ringing"),
    DELIVERED("delivered"),
    ACKNOWLEDGED("acknowledged"),
    NOT_DELIVERED("not_delivered"),
    ANSWERED("answered"),
    UNKNOWN("");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it != UNKNOWN && it.wire == wire } ?: UNKNOWN
    }
}

/** `session_end.reason`; an unknown one is [ENDED_BY_PEER] (a generic end of the session, section 11.1). */
enum class SessionEndReason(val wire: String) {
    ENDED_BY_YOU("ended_by_you"),
    ENDED_BY_PEER("ended_by_peer"),
    IDLE("idle"),
    MISSED("missed"),
    FAILED("failed");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: ENDED_BY_PEER
    }
}

/** `floor_released.reason`; an unknown one is [RELEASED]. */
enum class ReleaseReason(val wire: String) {
    RELEASED("released"),
    SILENCE("silence"),
    MAX_LENGTH("max_length"),
    DISCONNECTED("disconnected");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: RELEASED
    }
}

/** `error.code`; an unknown one is [UNKNOWN] and non-fatal. */
enum class WsErrorCode(val wire: String) {
    NOT_FOUND("not_found"),
    NO_PEER("no_peer"),
    BAD_TRANSMISSION_ID("bad_transmission_id"),
    UNKNOWN("");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it != UNKNOWN && it.wire == wire } ?: UNKNOWN
    }
}

/** Messages the server sends. Ids are canonical lowercase UUIDs (checked by the decoder). */
sealed interface ServerMessage {
    data class Hello(val protocol: Int, val deviceId: String, val serverTime: Long) : ServerMessage

    data class PageState(val pageId: String, val state: WsPageState) : ServerMessage

    data class PeerJoined(val deviceId: String) : ServerMessage

    data class PeerLeft(val deviceId: String) : ServerMessage

    data class FloorGranted(val talker: String, val transmissionId: String) : ServerMessage

    data object FloorBusy : ServerMessage

    data class FloorReleased(val talker: String, val transmissionId: String, val reason: ReleaseReason) :
        ServerMessage

    data class SessionEnd(val reason: SessionEndReason) : ServerMessage

    data class Error(val code: WsErrorCode) : ServerMessage

    data class Pong(val t: Long, val serverTime: Long) : ServerMessage
}

/**
 * The text-frame codec of docs/protocol.md section 11. [encode] is strict (exactly the keys of the message, ids
 * validated, at most [MAX_TEXT_BYTES]); [decode] is lenient the way section 11.1 requires of clients: an unknown
 * `type` and unknown fields are ignored, unknown enum values map to their documented fallback. A message of a
 * known type that lacks a field or has a malformed one is dropped too (null): it cannot be acted on. Nothing here
 * logs, and no exception text carries a value.
 */
object TalkCodec {
    const val MAX_TEXT_BYTES = 1024
    private const val MAX_SAFE_INT = 9_007_199_254_740_991L
    private const val TOKEN_LENGTH = 43
    private val json = Json

    @Suppress("CyclomaticComplexMethod")
    fun encode(message: ClientMessage): String {
        val obj = buildJsonObject {
            when (message) {
                is ClientMessage.Hello -> {
                    ensure(message.protocol >= 1) { "protocol must be at least 1" }
                    ensure(message.token.length == TOKEN_LENGTH) { "token is not 43 characters" }
                    put("type", "hello")
                    put("protocol", message.protocol)
                    put("token", message.token)
                    put("app_version", message.appVersion)
                }

                is ClientMessage.Join -> {
                    Validation.requireUuid(message.sessionId, "session id")
                    put("type", "join")
                    put("session_id", message.sessionId)
                }

                is ClientMessage.FloorRequest -> {
                    Validation.requireUuid(message.transmissionId, "transmission id")
                    put("type", "floor_request")
                    put("transmission_id", message.transmissionId)
                }

                is ClientMessage.FloorRelease -> {
                    Validation.requireUuid(message.transmissionId, "transmission id")
                    put("type", "floor_release")
                    put("transmission_id", message.transmissionId)
                }

                ClientMessage.SessionEnd -> put("type", "session_end")

                is ClientMessage.Ping -> {
                    ensure(message.t in 0..MAX_SAFE_INT) { "t out of range" }
                    put("type", "ping")
                    put("t", message.t)
                }
            }
        }
        val text = json.encodeToString(JsonObject.serializer(), obj)
        ensure(text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "text frame too large" }
        return text
    }

    /** The message, or null when it is not an object, has an unknown `type`, or lacks what its type needs. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun decode(text: String): ServerMessage? {
        val obj = parse(text) ?: return null
        return when (obj.str("type")) {
            "hello" -> {
                val protocol = obj.long("protocol")?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
                val deviceId = obj.uuid("device_id") ?: return null
                ServerMessage.Hello(protocol.toInt(), deviceId, obj.long("server_time") ?: return null)
            }

            "page_state" -> ServerMessage.PageState(
                obj.uuid("page_id") ?: return null,
                WsPageState.of(obj.str("state") ?: return null)
            )

            "peer_joined" -> ServerMessage.PeerJoined(obj.uuid("device_id") ?: return null)

            "peer_left" -> ServerMessage.PeerLeft(obj.uuid("device_id") ?: return null)

            "floor_granted" -> ServerMessage.FloorGranted(
                obj.uuid("talker") ?: return null,
                obj.uuid("transmission_id") ?: return null
            )

            "floor_busy" -> ServerMessage.FloorBusy

            "floor_released" -> ServerMessage.FloorReleased(
                obj.uuid("talker") ?: return null,
                obj.uuid("transmission_id") ?: return null,
                ReleaseReason.of(obj.str("reason") ?: return null)
            )

            "session_end" -> ServerMessage.SessionEnd(SessionEndReason.of(obj.str("reason") ?: return null))

            "error" -> ServerMessage.Error(WsErrorCode.of(obj.str("code") ?: return null))

            "pong" -> ServerMessage.Pong(obj.long("t") ?: return null, obj.long("server_time") ?: return null)

            else -> null
        }
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p.isString) null else p.longOrNull?.takeIf { it >= 0 }
    }

    private fun JsonObject.uuid(key: String): String? = str(key)?.takeIf(Validation::isUuid)
}
