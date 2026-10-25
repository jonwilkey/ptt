package com.example.ptt.crypto

import java.security.SecureRandom

/** A parsed page envelope payload (docs/protocol.md section 7). [keys] maps device id to enc || ct. */
// The constructor mirrors the protocol's fixed envelope fields.
@Suppress("LongParameterList")
class EnvelopePayload(
    val pageId: String,
    val sessionId: String,
    val fromDevice: String,
    val toUser: String,
    val level: String,
    val createdAt: Long,
    val keys: Map<String, ByteArray>
) {
    internal fun encode(): ByteArray {
        val keyList = keys.toSortedMap().entries.joinToString(",") { """"${it.key}":"${B64u.encode(it.value)}"""" }
        return (
            """{"format":$FORMAT,"page_id":"$pageId","session_id":"$sessionId","from_device":"$fromDevice",""" +
                """"to_user":"$toUser","level":${JsonText.quote(level)},"created_at":$createdAt,"keys":{$keyList}}"""
            ).toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val FORMAT = 1L
        const val LEVEL_NORMAL = "normal"
        const val LEVEL_URGENT = "urgent"
        const val WRAPPED_KEY_BYTES = 80
        const val MAX_RECIPIENTS = 64

        /** Strictly parses an envelope payload, as the server does; throws [ProtocolException]. */
        fun parse(payload: ByteArray): EnvelopePayload {
            val top = StrictJson.parse(payload).asObject("envelope").exactly(
                "format",
                "page_id",
                "session_id",
                "from_device",
                "to_user",
                "level",
                "created_at",
                "keys"
            )
            ensure(top.number("format") == FORMAT) { "unsupported envelope format" }
            val envelope = EnvelopePayload(
                pageId = top.string("page_id"),
                sessionId = top.string("session_id"),
                fromDevice = top.string("from_device"),
                toUser = top.string("to_user"),
                level = top.string("level"),
                createdAt = top.number("created_at"),
                keys = top.obj("keys").members.mapValues { (id, value) ->
                    Validation.requireUuid(id, "keys entry id")
                    val text = (value as? JsonValue.Str)?.value ?: protocolError("wrapped key must be a string")
                    B64u.decodeExact(text, WRAPPED_KEY_BYTES, "wrapped key")
                }
            )
            envelope.requireWellFormed()
            return envelope
        }
    }

    private fun requireWellFormed() {
        listOf("page_id" to pageId, "session_id" to sessionId, "from_device" to fromDevice, "to_user" to toUser)
            .forEach { (name, id) -> Validation.requireUuid(id, name) }
        ensure(level == LEVEL_NORMAL || level == LEVEL_URGENT) { "bad level" }
        ensure(createdAt > 0) { "created_at must be positive" }
        ensure(keys.size in 1..MAX_RECIPIENTS) { "keys must have 1 to $MAX_RECIPIENTS entries" }
    }
}

/** A freshly built envelope and the session key it carries (kept by the sender for the audio session). */
class BuiltEnvelope(val signed: SignedObject, val payload: EnvelopePayload, val sessionKey: ByteArray)

/** The push fields a receiver compares against the envelope. [fromUser] is the server-reported sender user. */
class PagePush(val pageId: String, val level: String, val fromUser: String)

enum class DropReason {
    UNKNOWN_SIGNER,
    BAD_SIGNATURE,
    MALFORMED_PAYLOAD,
    SIGNER_MISMATCH,
    PAGE_ID_MISMATCH,
    LEVEL_MISMATCH,
    NOT_FOR_THIS_USER,
    NO_KEY_FOR_THIS_DEVICE,
    FROM_USER_MISMATCH,
    DECRYPT_FAILED
}

sealed interface ReceiveResult {
    /** [sender] is the roster entry: the caller name shown to the user comes from it, never the server. */
    class Accepted(val envelope: EnvelopePayload, val sender: RosterDevice, val sessionKey: ByteArray) : ReceiveResult
    class Dropped(val reason: DropReason) : ReceiveResult
}

/** Builds and receives page envelopes (docs/protocol.md section 7). */
object PageEnvelopes {
    private const val INFO_LABEL = "ptt-session-key-v1"
    private const val SESSION_KEY_BYTES = 32

    /** `"ptt-session-key-v1" || 0x00 || page_id || session_id || recipient device_id`, all ASCII. */
    fun info(pageId: String, sessionId: String, recipientDeviceId: String): ByteArray =
        "$INFO_LABEL\u0000$pageId$sessionId$recipientDeviceId".toByteArray(Charsets.US_ASCII)

    /**
     * Builds and signs an envelope from [senderId] to user [toUser]: a fresh 32-byte session key from
     * [random], wrapped for exactly the devices of [toUser] in the verified [roster] (never from the
     * server's contacts list). The sender must be an admin-or-not entry of [roster] whose keys are [keys].
     */
    @Suppress("LongParameterList")
    fun build(
        roster: Roster,
        senderId: String,
        keys: DeviceKeys,
        toUser: String,
        pageId: String,
        sessionId: String,
        level: String,
        createdAt: Long,
        random: SecureRandom = SecureRandom()
    ): BuiltEnvelope {
        val sender = roster.find(senderId)
        ensure(sender != null && sender.ed25519.contentEquals(keys.ed25519Public)) { "sender is not in the roster" }
        val recipients = roster.devicesOf(toUser)
        ensure(recipients.isNotEmpty()) { "no devices for the recipient in the roster" }
        val sessionKey = ByteArray(SESSION_KEY_BYTES).also(random::nextBytes)
        val wrapped = recipients.associate { device ->
            val ciphertext = TinkKeys.hpkeEncrypter(device.x25519)
                .encrypt(sessionKey, info(pageId, sessionId, device.id))
            device.id to ciphertext
        }
        val payload = EnvelopePayload(pageId, sessionId, senderId, toUser, level, createdAt, wrapped)
        val bytes = payload.encode()
        // Reject anything the server or a receiver would reject, before it is signed.
        EnvelopePayload.parse(bytes)
        return BuiltEnvelope(keys.signObject(Contexts.ENVELOPE, senderId, bytes), payload, sessionKey)
    }

    /**
     * Applies the section 7 receiver checks in order and unwraps this device's session key. Any failure
     * drops the message; nothing here is logged.
     */
    // The section 7 checks are a flat list of drop conditions; splitting them would hide the order.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun receive(
        roster: Roster,
        selfId: String,
        keys: DeviceKeys,
        signedEnvelope: SignedObject,
        push: PagePush
    ): ReceiveResult {
        val sender = roster.find(signedEnvelope.signer)
            ?: return drop(DropReason.UNKNOWN_SIGNER)
        val message = SignedObject.message(Contexts.ENVELOPE, signedEnvelope.payload)
        if (!TinkKeys.verifyEd25519(sender.ed25519, message, signedEnvelope.sig)) return drop(DropReason.BAD_SIGNATURE)
        val envelope = try {
            EnvelopePayload.parse(signedEnvelope.payload)
        } catch (_: ProtocolException) {
            return drop(DropReason.MALFORMED_PAYLOAD)
        }
        val self = roster.find(selfId)
        val wrapped = envelope.keys[selfId]
        val failure = when {
            envelope.fromDevice != signedEnvelope.signer -> DropReason.SIGNER_MISMATCH
            envelope.pageId != push.pageId -> DropReason.PAGE_ID_MISMATCH
            envelope.level != push.level -> DropReason.LEVEL_MISMATCH
            self == null || envelope.toUser != self.userId -> DropReason.NOT_FOR_THIS_USER
            sender.userId != push.fromUser -> DropReason.FROM_USER_MISMATCH
            wrapped == null -> DropReason.NO_KEY_FOR_THIS_DEVICE
            else -> null
        }
        if (failure != null || wrapped == null) return drop(failure ?: DropReason.NO_KEY_FOR_THIS_DEVICE)
        val sessionKey = try {
            keys.unwrap(wrapped, info(envelope.pageId, envelope.sessionId, selfId))
        } catch (_: ProtocolException) {
            return drop(DropReason.DECRYPT_FAILED)
        }
        return ReceiveResult.Accepted(envelope, sender, sessionKey)
    }

    private fun drop(reason: DropReason): ReceiveResult = ReceiveResult.Dropped(reason)
}
