package com.example.ptt.talk

/**
 * The first-talker rule (spec, "Talk screen"): the receiver always speaks first. The sender's button stays locked
 * until the receiver's first transmission has ended ([onPeerStoppedTalking]), or [WINDOW_MS] pass in silence after
 * the peer is first seen present. If the receiver starts talking inside the window the timer stops and only the end
 * of their transmission unlocks. Only the sender has one: the receiver is never locked.
 *
 * A pure class with no clock of its own: every call passes `now` (milliseconds on any monotonic scale), so tests
 * drive it with a virtual clock. Once unlocked it stays unlocked, and only the first sight of the peer starts the
 * timer, so the join snapshot a reconnect replays (a repeated `peer_joined`) cannot restart the 10 s. One instance
 * per live session ([TalkSessionController]), which outlives reconnects and the screen.
 */
class FirstTalkerRule {
    private var unlocked = false
    private var deadline: Long? = null
    private var peerTalking = false

    /** The peer is in the conversation (a `peer_joined`, live or from a join snapshot). */
    fun onPeerPresent(now: Long) {
        settle(now)
        if (unlocked || peerTalking || deadline != null) return
        deadline = now + WINDOW_MS
    }

    /** The peer was granted the floor: the end of their transmission, not the clock, decides from here. */
    fun onPeerStartedTalking(now: Long) {
        settle(now)
        if (unlocked) return
        peerTalking = true
        deadline = null
    }

    /** The peer's transmission ended, however: their release, a lost socket, or the session ending. */
    fun onPeerStoppedTalking() {
        unlocked = true
    }

    fun isLocked(now: Long): Boolean {
        settle(now)
        return !unlocked
    }

    /** The lock lifting by the clock is final: a later peer transmission must not bring it back. */
    private fun settle(now: Long) {
        val due = deadline
        if (due != null && now >= due) unlocked = true
    }

    /** When the lock will lift by itself, if a timer is running. */
    fun deadline(): Long? = if (unlocked) null else deadline

    companion object {
        const val WINDOW_MS = 10_000L
    }
}
