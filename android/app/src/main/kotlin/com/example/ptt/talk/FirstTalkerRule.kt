package com.example.ptt.talk

/**
 * The first-talker rule (spec, "Talk screen"): the receiver always speaks first. The sender's button stays locked
 * until the receiver's first transmission has ended ([onPeerStoppedTalking]), or [WINDOW_MS] pass in silence after
 * the peer is first seen present. If the receiver starts talking inside the window the timer stops and only the end
 * of their transmission unlocks. Only the sender has one: the receiver is never locked.
 *
 * When this phone's own socket drops during that first transmission, the transmission is suspended, not over
 * (docs/protocol.md section 12.4): the rejoin snapshot may resume it with the same id. It counts as over once the
 * snapshot is complete without resuming it ([onRejoinSettled]) or the peer starts another transmission.
 *
 * A pure class with no clock of its own: every call passes `now` (milliseconds on any monotonic scale), so tests
 * drive it with a virtual clock. Once unlocked it stays unlocked, and only the first sight of the peer starts the
 * timer, so the join snapshot a reconnect replays (a repeated `peer_joined`) cannot restart the 10 s. One instance
 * per live session ([TalkSessionController]), which outlives reconnects and the screen.
 */
class FirstTalkerRule {
    private sealed interface Phase {
        /** Nobody has talked yet; [deadline] runs once the peer has been seen. */
        data class Waiting(val deadline: Long?) : Phase

        data class PeerTalking(val transmissionId: String) : Phase

        /** The own socket dropped during [transmissionId]; the rejoin decides whether it goes on. */
        data class Suspended(val transmissionId: String) : Phase

        data object Unlocked : Phase
    }

    private var phase: Phase = Phase.Waiting(null)

    /** The peer is in the conversation (a `peer_joined`, live or from a join snapshot). */
    fun onPeerPresent(now: Long) {
        settle(now)
        if (phase == Phase.Waiting(null)) phase = Phase.Waiting(now + WINDOW_MS)
    }

    /** The peer was granted [transmissionId]: the end of their transmission, not the clock, decides from here. */
    fun onPeerStartedTalking(now: Long, transmissionId: String) {
        settle(now)
        phase = when (val p = phase) {
            is Phase.Waiting -> Phase.PeerTalking(transmissionId)

            // The same id is the suspended transmission resuming; another one means it ended.
            is Phase.Suspended ->
                if (p.transmissionId == transmissionId) Phase.PeerTalking(transmissionId) else Phase.Unlocked

            is Phase.PeerTalking, Phase.Unlocked -> p
        }
    }

    /** The peer's transmission ended: their release, or the session ending. */
    fun onPeerStoppedTalking() {
        phase = Phase.Unlocked
    }

    /** This phone's socket closed: a transmission of the peer's in progress is suspended. */
    fun onOwnSocketLost() {
        val p = phase
        if (p is Phase.PeerTalking) phase = Phase.Suspended(p.transmissionId)
    }

    /** The rejoin snapshot is complete: a suspended transmission it did not resume is over. */
    fun onRejoinSettled() {
        if (phase is Phase.Suspended) phase = Phase.Unlocked
    }

    fun isLocked(now: Long): Boolean {
        settle(now)
        return phase != Phase.Unlocked
    }

    /** The lock lifting by the clock is final: a later peer transmission must not bring it back. */
    private fun settle(now: Long) {
        val due = (phase as? Phase.Waiting)?.deadline
        if (due != null && now >= due) phase = Phase.Unlocked
    }

    /** When the lock will lift by itself, if a timer is running. */
    fun deadline(): Long? = (phase as? Phase.Waiting)?.deadline

    companion object {
        const val WINDOW_MS = 10_000L
    }
}
