package com.example.ptt.push

import com.example.ptt.alert.AlertEngine
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.PageRole
import java.time.Clock

/**
 * Which active-page row a page may replace: the one place that decides whether the phone's single page slot is
 * still taken. The dispatcher asks it for an incoming page, [com.example.ptt.talk.PageSender] for one about to
 * be sent.
 */
class ActivePageRules(private val engine: AlertEngine, private val sessionKeys: SessionKeys, private val clock: Clock) {
    /**
     * A row is replaceable by page [pageId] (urgent or not) when its page is over or can no longer be live:
     * - the same page, or a row in state `ended`, or a sender row in a terminal state (missed, failed, ended);
     * - an answered row untouched for longer than a session can last (an answered page is deleted by the
     *   server 15 minutes after the answer);
     * - a sender row still `sending` after the send window (the page was never accepted, see ADR 0007);
     * - a receiver row this process knows nothing of (no ringing entry, no session key): left behind by a
     *   process death;
     * - a ringing normal receiver row that an urgent page outranks or that is overdue (a normal page is missed
     *   after 15 minutes).
     */
    fun isReplaceable(current: ActivePageEntity, pageId: String, urgent: Boolean): Boolean {
        val now = clock.millis()
        val receiver = current.role == PageRole.RECEIVER
        val over = current.pageId == pageId || current.state == STATE_ENDED ||
            (!receiver && current.state in SENDER_TERMINAL)
        return over || stale(current, now) || orphan(current) || outranked(current, urgent, now)
    }

    private fun stale(current: ActivePageEntity, now: Long): Boolean {
        val answered = current.state == STATE_ANSWERED && now - current.updatedAt > STALE_ANSWERED_MS
        val sending = current.role == PageRole.SENDER && current.state == STATE_SENDING &&
            now - current.createdAt > STALE_ANSWERED_MS
        return answered || sending
    }

    private fun orphan(current: ActivePageEntity) = current.role == PageRole.RECEIVER &&
        !engine.knows(current.pageId) && !sessionKeys.has(current.pageId)

    private fun outranked(current: ActivePageEntity, urgent: Boolean, now: Long): Boolean {
        val ringingNormal = current.role == PageRole.RECEIVER && current.state != STATE_ANSWERED &&
            current.level == EnvelopePayload.LEVEL_NORMAL
        return ringingNormal && (urgent || now - current.createdAt > OVERDUE_NORMAL_MS)
    }

    companion object {
        /**
         * How far a sender's page is: a state only replaces one of lower rank, so a late `delivered` (normal
         * priority) never takes a row back from `answered`. Unknown states rank below all.
         */
        fun senderRank(state: String): Int = SENDER_ORDER.indexOf(state)

        /** A sender row in such a state is over: nothing moves it again. */
        fun isSenderTerminal(state: String): Boolean = state in SENDER_TERMINAL

        /** A normal page is missed after 15 minutes, so a ringing normal row older than this is stale. */
        const val OVERDUE_NORMAL_MS = 16 * 60 * 1_000L

        /** An answered page is deleted by the server 15 minutes after the answer; this adds a minute of margin. */
        const val STALE_ANSWERED_MS = 16 * 60 * 1_000L
        const val STATE_SENDING = "sending"
        private const val STATE_ANSWERED = "answered"
        private const val STATE_ENDED = "ended"
        private val SENDER_ORDER = listOf(STATE_SENDING, "not_delivered", "delivered", "acknowledged", STATE_ANSWERED)
        private val SENDER_TERMINAL = setOf("missed", "failed", STATE_ENDED)
    }
}
