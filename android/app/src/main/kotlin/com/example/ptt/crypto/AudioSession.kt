package com.example.ptt.crypto

/** What the caller must do after [AudioSession.onFloorGranted]. */
sealed interface GrantOutcome {
    /** This phone is the talker: send audio by sealing Opus packets with [sealer]. */
    class Transmit(val sealer: FrameSealer) : GrantOutcome

    /** Send `floor_release {transmission_id}` and no audio. */
    class SendRelease(val transmissionId: String) : GrantOutcome

    /** The peer is talking: feed binary frames to [AudioSession.onFrame]. */
    data object Listen : GrantOutcome

    /** Nothing to do (duplicate self grant, refused by rule 4, or an unexpected talker). */
    data object Ignored : GrantOutcome
}

/**
 * Per-session end-to-end audio state (docs/protocol.md section 12): owns a copy of the 32-byte session
 * key (memory only), the listener-side [FrameReceiver] and the talker-side [TalkerFloor], and zeroes
 * the key on [close]. Create one when the live WebSocket session starts, with the key taken from
 * `SessionKeys` (the sender's own key lives there too), and close it when the session ends.
 *
 * Create it with [forAnswerer] or [forSender]; the role fixes who the peer may be (section 11.6).
 * Take the key with `SessionKeys.copy(pageId)` (atomic with `clear`, never a zeroed key); an all-zero
 * or wrong-sized key is refused with [ProtocolException].
 *
 * Wiring (milestone 4, TalkService): on the PTT button down send `floor_request {requestFloor()}`;
 * route `floor_granted` / `floor_released` / `floor_busy` / `error` / `peer_joined` to the matching
 * `on...` method; on [GrantOutcome.SendRelease] send `floor_release`; on [GrantOutcome.Transmit] seal
 * each 20 ms Opus packet with [FrameSealer.seal] and send it as a binary frame; on button up send
 * `floor_release` for [buttonReleased]'s result; on every binary frame call [onFrame] and play only
 * [FrameVerdict.Accepted]; call [onSocketClosed] when the socket closes. Thread-safe.
 */
class AudioSession private constructor(
    sessionKey: ByteArray,
    private val sessionId: String,
    private val ownDeviceId: String,
    private val fixedPeer: String?,
    private val envelopeKeyDevices: Set<String>,
    mintTransmissionId: (() -> String)?
) {
    private val key: ByteArray = sessionKey.copyOf()
    private val receiver = FrameReceiver(key, sessionId, fixedPeer)
    private val floor = if (mintTransmissionId != null) TalkerFloor(mintTransmissionId) else TalkerFloor()
    private var sealer: FrameSealer? = null
    private var closed = false

    private var senderPeer: String? = null

    init {
        Validation.requireUuid(ownDeviceId, "own device id")
        fixedPeer?.let { Validation.requireUuid(it, "peer device id") }
    }

    companion object {
        /** Answering device: the only peer is the envelope's `from_device`, and it never changes. */
        fun forAnswerer(sessionKey: ByteArray, sessionId: String, ownDeviceId: String, fromDevice: String) =
            forAnswerer(sessionKey, sessionId, ownDeviceId, fromDevice, null)

        /**
         * Sending device: the peer is the first `peer_joined` device that is in the envelope's `keys`
         * ([envelopeKeyDevices]); it is then pinned for the session.
         */
        fun forSender(sessionKey: ByteArray, sessionId: String, ownDeviceId: String, envelopeKeyDevices: Set<String>) =
            forSender(sessionKey, sessionId, ownDeviceId, envelopeKeyDevices, null)

        internal fun forAnswerer(
            sessionKey: ByteArray,
            sessionId: String,
            ownDeviceId: String,
            fromDevice: String,
            mint: (() -> String)?
        ): AudioSession {
            Validation.requireUuid(fromDevice, "from device")
            return AudioSession(sessionKey, sessionId, ownDeviceId, fromDevice, emptySet(), mint)
        }

        internal fun forSender(
            sessionKey: ByteArray,
            sessionId: String,
            ownDeviceId: String,
            envelopeKeyDevices: Set<String>,
            mint: (() -> String)?
        ) = AudioSession(sessionKey, sessionId, ownDeviceId, null, envelopeKeyDevices, mint)
    }

    /** Mints and returns the fresh `transmission_id` for a `floor_request`. */
    @Synchronized
    fun requestFloor(): String {
        ensure(!closed) { "session closed" }
        return floor.request()
    }

    /** Button up. Returns the transmission id to send `floor_release` for, or null if none was active. */
    @Synchronized
    fun buttonReleased(): String? {
        if (closed) return null
        val id = floor.buttonReleased()
        endSealer()
        return id
    }

    /**
     * `peer_joined {device_id}`. The answerer accepts only its `from_device` (and never changes it). The
     * sender accepts a device in the envelope's `keys` and pins the first one for the session; a later
     * `peer_joined` for the same device is accepted again, for any other it is refused.
     */
    @Synchronized
    @Suppress("ReturnCount")
    fun onPeerJoined(deviceId: String): Boolean {
        if (closed) return false
        if (fixedPeer != null) return deviceId == fixedPeer
        if (deviceId !in envelopeKeyDevices || deviceId == ownDeviceId) return false
        val pinned = senderPeer
        if (pinned != null) return deviceId == pinned
        senderPeer = deviceId
        receiver.setExpectedPeer(deviceId)
        return true
    }

    /** `floor_granted {talker, transmission_id}` (docs/protocol.md section 11.6). */
    @Synchronized
    @Suppress("ReturnCount")
    fun onFloorGranted(talker: String, transmissionId: String): GrantOutcome {
        if (closed || !Validation.isUuid(transmissionId) || !Validation.isUuid(talker)) return GrantOutcome.Ignored
        if (talker != ownDeviceId) {
            return when (receiver.onGranted(talker, transmissionId)) {
                GrantVerdict.ACCEPTED, GrantVerdict.RESUMED -> GrantOutcome.Listen
                GrantVerdict.REFUSED, GrantVerdict.IGNORED -> GrantOutcome.Ignored
            }
        }
        return when (val grant = floor.onGranted(transmissionId)) {
            is SelfGrant.Transmit -> {
                endSealer()
                val tk = FrameCipher.deriveKey(key, sessionId, ownDeviceId, grant.transmissionId)
                val s = FrameSealer(grant.transmissionId, tk)
                sealer = s
                GrantOutcome.Transmit(s)
            }

            is SelfGrant.Release -> GrantOutcome.SendRelease(grant.transmissionId)

            SelfGrant.Duplicate -> GrantOutcome.Ignored
        }
    }

    /** `floor_released {transmission_id}`. */
    @Synchronized
    fun onFloorReleased(transmissionId: String) {
        receiver.onReleased(transmissionId)
        floor.onReleased(transmissionId)
        if (sealer?.transmissionId == transmissionId) endSealer()
    }

    /** `floor_busy` or `error` answering the request for [transmissionId]. */
    @Synchronized
    fun onRequestFailed(transmissionId: String) = floor.onRequestFailed(transmissionId)

    /** The socket closed: listening is suspended and any own transmission is lost (a new id is needed). */
    @Synchronized
    fun onSocketClosed() {
        receiver.onSocketClosed()
        floor.onSocketClosed()
        endSealer()
    }

    /** A binary frame from the server. */
    fun onFrame(frame: ByteArray): FrameVerdict = receiver.onFrame(frame)

    /** Zeroes the session key and every derived key and drops all state. Idempotent. */
    @Synchronized
    fun close() {
        closed = true
        endSealer()
        receiver.close()
        key.fill(0)
    }

    @get:Synchronized
    internal val keyIsZeroed: Boolean get() = key.all { it == 0.toByte() } && receiver.isClosed

    private fun endSealer() {
        sealer?.close()
        sealer = null
    }
}
