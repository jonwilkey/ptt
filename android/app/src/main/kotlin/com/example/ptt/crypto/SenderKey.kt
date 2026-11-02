package com.example.ptt.crypto

/** A parsed sender key payload (docs/protocol.md section 7): the session key wrapped for the sender's own device. */
class SenderKeyPayload(val pageId: String, val sessionId: String, val fromDevice: String, val key: ByteArray) {
    internal fun encode(): ByteArray = (
        """{"format":$FORMAT,"page_id":"$pageId","session_id":"$sessionId","from_device":"$fromDevice",""" +
            """"key":"${B64u.encode(key)}"}"""
        ).toByteArray(Charsets.UTF_8)

    companion object {
        const val FORMAT = 1L

        /** Strictly parses a sender key payload, as the server does; throws [ProtocolException]. */
        fun parse(payload: ByteArray): SenderKeyPayload {
            val top = StrictJson.parse(payload).asObject("sender key")
                .exactly("format", "page_id", "session_id", "from_device", "key")
            ensure(top.number("format") == FORMAT) { "unsupported sender key format" }
            val parsed = SenderKeyPayload(
                top.string("page_id"),
                top.string("session_id"),
                top.string("from_device"),
                B64u.decodeExact(top.string("key"), EnvelopePayload.WRAPPED_KEY_BYTES, "wrapped key")
            )
            listOf("page_id" to parsed.pageId, "session_id" to parsed.sessionId, "from_device" to parsed.fromDevice)
                .forEach { (name, id) -> Validation.requireUuid(id, name) }
            return parsed
        }
    }
}

/** The first check of docs/protocol.md section 13.7 (steps 1 to 4) that a recovered page failed. */
enum class RecoveryFailure {
    ENVELOPE_NOT_OWN,
    ENVELOPE_BAD_SIGNATURE,
    ENVELOPE_BAD_PAYLOAD,
    PAGE_ID_MISMATCH,
    SENDER_KEY_NOT_OWN,
    SENDER_KEY_BAD_SIGNATURE,
    SENDER_KEY_BAD_PAYLOAD,
    SENDER_KEY_MISMATCH,
    DECRYPT_FAILED
}

sealed interface RecoveryResult {
    /** Everything a sender needs to take part again; [senderKey] is kept to resend after each join. */
    class Recovered(val envelope: EnvelopePayload, val senderKey: SignedObject, val sessionKey: ByteArray) :
        RecoveryResult

    class Failed(val reason: RecoveryFailure) : RecoveryResult
}

/** The sender's own copy of the session key (docs/protocol.md sections 7 and 13.7). */
object SenderKeys {
    private const val INFO_LABEL = "ptt-sender-session-key-v1"

    /** `"ptt-sender-session-key-v1" || 0x00 || page_id || session_id || sender device_id`, all ASCII. */
    fun info(pageId: String, sessionId: String, senderDeviceId: String): ByteArray =
        "$INFO_LABEL\u0000$pageId$sessionId$senderDeviceId".toByteArray(Charsets.US_ASCII)

    /** Wraps [sessionKey] to this device's own X25519 key and signs it as [senderId]. */
    fun build(keys: DeviceKeys, senderId: String, pageId: String, sessionId: String, sessionKey: ByteArray) =
        TinkKeys.hpkeEncrypter(keys.x25519Public)
            .encrypt(sessionKey, info(pageId, sessionId, senderId))
            .let { SenderKeyPayload(pageId, sessionId, senderId, it).encode() }
            .let { keys.signObject(Contexts.SENDER_KEY, senderId, it) }

    /**
     * Section 13.7 steps 2 to 4 for a page returned by `GET /v1/pages/{id}`, using only this device's own keys
     * ([ed25519] is its public signing key, [keys] opens the wrap), stopping at the first failing check.
     */
    @Suppress("ReturnCount", "LongParameterList", "CyclomaticComplexMethod")
    fun recover(
        self: String,
        ed25519: ByteArray,
        keys: DeviceKeys,
        pushPageId: String,
        envelope: SignedObject,
        senderKey: SignedObject
    ): RecoveryResult {
        if (envelope.signer != self) return failed(RecoveryFailure.ENVELOPE_NOT_OWN)
        if (!verify(ed25519, Contexts.ENVELOPE, envelope)) return failed(RecoveryFailure.ENVELOPE_BAD_SIGNATURE)
        val page = try {
            EnvelopePayload.parse(envelope.payload)
        } catch (_: ProtocolException) {
            return failed(RecoveryFailure.ENVELOPE_BAD_PAYLOAD)
        }
        if (page.fromDevice != self) return failed(RecoveryFailure.ENVELOPE_NOT_OWN)
        if (page.pageId != pushPageId) return failed(RecoveryFailure.PAGE_ID_MISMATCH)
        if (senderKey.signer != self) return failed(RecoveryFailure.SENDER_KEY_NOT_OWN)
        if (!verify(ed25519, Contexts.SENDER_KEY, senderKey)) return failed(RecoveryFailure.SENDER_KEY_BAD_SIGNATURE)
        val wrap = try {
            SenderKeyPayload.parse(senderKey.payload)
        } catch (_: ProtocolException) {
            return failed(RecoveryFailure.SENDER_KEY_BAD_PAYLOAD)
        }
        if (wrap.pageId != page.pageId || wrap.sessionId != page.sessionId) {
            return failed(RecoveryFailure.SENDER_KEY_MISMATCH)
        }
        if (wrap.fromDevice != self) return failed(RecoveryFailure.SENDER_KEY_NOT_OWN)
        val sessionKey = try {
            keys.unwrap(wrap.key, info(wrap.pageId, wrap.sessionId, self))
        } catch (_: ProtocolException) {
            return failed(RecoveryFailure.DECRYPT_FAILED)
        }
        return RecoveryResult.Recovered(page, senderKey, sessionKey)
    }

    private fun verify(ed25519: ByteArray, ctx: String, signed: SignedObject) =
        TinkKeys.verifyEd25519(ed25519, SignedObject.message(ctx, signed.payload), signed.sig)

    private fun failed(reason: RecoveryFailure): RecoveryResult = RecoveryResult.Failed(reason)
}
