package com.example.ptt.crypto

import java.util.UUID

/** What to do with a `floor_granted` that names this phone as talker (docs/protocol.md sections 11.6, 12.4). */
sealed interface SelfGrant {
    /** The grant answers the outstanding request: start sending under this id. */
    class Transmit(val transmissionId: String) : SelfGrant

    /** Send `floor_release` for this id and send no audio (unsolicited, stale, or the button is up). */
    class Release(val transmissionId: String) : SelfGrant

    /** A repeat of the grant already being transmitted under: do nothing. */
    data object Duplicate : SelfGrant
}

/**
 * Talker-side floor state: mints the `transmission_id` of every `floor_request` and decides whether a
 * grant naming this phone may be used (docs/protocol.md section 12.4 rules 1 and 2, section 11.6).
 * Not thread-safe by itself; [AudioSession] serializes access.
 */
class TalkerFloor internal constructor(private val mint: () -> String = { UUID.randomUUID().toString() }) {
    private val used = HashSet<String>()
    private var outstanding: String? = null
    private var wanted = false
    private var active: String? = null

    /** The id of the transmission this phone is sending, if any. */
    val activeTransmission: String? get() = active

    /**
     * Mints a fresh random version-4 id for a new `floor_request` and makes it the outstanding request
     * (replacing any earlier one, whose late grant would then be answered with a release). The id is
     * never reused, whatever happens to the request.
     */
    fun request(): String {
        val id = mint()
        ensure(Validation.isUuid(id) && id[VERSION_INDEX] == '4') { "minted id is not a v4 UUID" }
        ensure(used.add(id)) { "transmission id minted twice" }
        outstanding = id
        wanted = true
        return id
    }

    /**
     * The push-to-talk button went up. Returns the id to send `floor_release` for if a transmission
     * was active. A request still awaiting its grant is remembered as unwanted: its grant will be
     * answered with a release.
     */
    fun buttonReleased(): String? {
        wanted = false
        val id = active
        active = null
        return id
    }

    /** Decides what to do with `floor_granted` naming this phone. */
    @Suppress("ReturnCount")
    fun onGranted(transmissionId: String): SelfGrant {
        if (transmissionId == active) return SelfGrant.Duplicate
        if (transmissionId != outstanding) return SelfGrant.Release(transmissionId)
        outstanding = null
        if (!wanted) return SelfGrant.Release(transmissionId)
        active = transmissionId
        return SelfGrant.Transmit(transmissionId)
    }

    /** `floor_busy` or `error` for [transmissionId]: the request is over; the id stays used. */
    fun onRequestFailed(transmissionId: String) {
        if (outstanding == transmissionId) outstanding = null
    }

    /** `floor_released` for [transmissionId]: sending (if it was this id) ends. */
    fun onReleased(transmissionId: String) {
        if (active == transmissionId) active = null
        if (outstanding == transmissionId) outstanding = null
    }

    /** The socket closed: the floor is lost (section 12.4 rule 3); a new request needs a new id. */
    fun onSocketClosed() {
        outstanding = null
        active = null
    }

    private companion object {
        const val VERSION_INDEX = 14
    }
}
