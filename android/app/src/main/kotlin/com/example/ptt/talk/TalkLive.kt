package com.example.ptt.talk

import com.example.ptt.audio.CaptureStatus

/** How good the connection is, from the round-trip time of the pings (spec: clip mode starts above 800 ms). */
enum class LinkQuality {
    GOOD,
    FAIR,
    POOR;

    companion object {
        const val GOOD_BELOW_MS = 300L
        const val FAIR_BELOW_MS = 800L

        fun of(rttMillis: Long?): LinkQuality? = when {
            rttMillis == null -> null
            rttMillis < GOOD_BELOW_MS -> GOOD
            rttMillis < FAIR_BELOW_MS -> FAIR
            else -> POOR
        }
    }
}

enum class Speaker {
    NONE,
    YOU,
    PEER;

    companion object {
        fun of(floor: TalkFloor) = when (floor) {
            TalkFloor.YOU -> YOU
            TalkFloor.PEER_TALKING -> PEER
            else -> NONE
        }
    }
}

/** The page is answered (by this phone, or by the receiver of the page this phone sent): the conversation. */
private val TalkState.answered: Boolean
    get() = phase == TalkPhase.ANSWERED ||
        (sender && phase == TalkPhase.OUTGOING && progress == SendProgress.ANSWERED)

/**
 * The live-talk half of the screen, derived from state only (never from one-shot events). [active] says the
 * conversation UI is shown at all; the rest is meaningful then.
 */
data class TalkLive(
    /** The page has a live session at all (false for the setup Test alert). */
    val eligible: Boolean = false,
    /** The conversation UI (button and status) is shown: the page is answered. */
    val active: Boolean = false,
    val connection: TalkConnection = TalkConnection.IDLE,
    val quality: LinkQuality? = null,
    val speaker: Speaker = Speaker.NONE,
    /** Connected, but the other phone is not in the conversation. */
    val waitingForPeer: Boolean = false,
    /** The sender is still waiting for the receiver to speak first (the first-talker rule). */
    val firstTalkerLocked: Boolean = false,
    val talkEnabled: Boolean = false,
    /** Why capture stopped by itself; IDLE and CAPTURING mean nothing to show. */
    val capture: CaptureStatus = CaptureStatus.IDLE
)

/**
 * What the live half of the screen shows for [screen] and the controller's [session] (already known to be this
 * page's, or empty). Durable UI comes from here, never from one-shot events.
 */
fun deriveLive(screen: TalkState, session: TalkSessionState, eligible: Boolean, capture: CaptureStatus): TalkLive {
    val active = eligible && screen.answered
    val live = active && session.connection == TalkConnection.LIVE
    val conversation = live && session.stage == SessionStage.CONVERSATION
    val ready = conversation && session.peerPresent
    return TalkLive(
        eligible = eligible,
        active = active,
        connection = session.connection,
        quality = if (live) LinkQuality.of(session.rttMillis) else null,
        speaker = Speaker.of(session.floor),
        waitingForPeer = conversation && !session.peerPresent,
        firstTalkerLocked = ready && session.firstTalkerLocked,
        talkEnabled = ready && !session.firstTalkerLocked && !screen.ending && !screen.microphoneMissing,
        capture = capture
    )
}
