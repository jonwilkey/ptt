package com.example.ptt.push

import android.util.Log
import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.IncomingPage
import com.example.ptt.alert.PageNotices
import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.DeviceKeysUnavailable
import com.example.ptt.crypto.PageEnvelopes
import com.example.ptt.crypto.PagePush
import com.example.ptt.crypto.ProtocolException
import com.example.ptt.crypto.ReceiveResult
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.SignedObject
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.PageRole
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.SessionEndReason
import com.example.ptt.net.WsPageState
import com.example.ptt.outbox.DeliveredOutcome
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxAction
import com.example.ptt.setup.RosterSync
import com.example.ptt.talk.TalkHosting
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Something the sender's screen may want to react to, published by [PushDispatcher.events]. */
sealed interface PageEvent {
    /** A `page_state` push for the page this phone sent. */
    data class State(val pageId: String, val state: String) : PageEvent

    /** The receiver answered the page this phone sent. */
    data class Answered(val pageId: String) : PageEvent

    /** A page this phone was paged with ended: [reason] is `cancelled`, `answered_elsewhere` or `missed`. */
    data class Ended(val pageId: String, val reason: String) : PageEvent
}

/** What the live-talk WebSocket feeds into the same progress model as FCM (docs/protocol.md section 8). */
interface SocketPageEvents {
    /** `page_state` over the WebSocket, for a page this phone sent. Monotonic and de-duplicated by page id. */
    suspend fun socketPageState(pageId: String, state: WsPageState)

    /** `session_end {reason}` (or the page found to be over) for [role]'s side of the page. */
    suspend fun sessionEnded(pageId: String, role: PageRole, reason: SessionEndReason)
}

/** Schedules a roster sync that survives the FCM service (an expedited WorkManager job). */
fun interface RosterSyncScheduler {
    fun schedule()
}

/**
 * Handles every FCM data message (docs/protocol.md section 9). It has no Android UI classes, so it is unit
 * tested and is also what the debug fake-push receiver calls. Call it from a background thread; it returns
 * when the message has been dealt with (for a `page`: after `delivered`, at most about [deliveredTimeoutMs]).
 *
 * Names shown to the user always come from the verified roster, never from push fields.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class PushDispatcher(
    private val rosterSync: RosterSync,
    private val tokens: DeviceTokenStore,
    private val keyStore: DeviceKeyStore,
    private val outbox: Outbox,
    private val engine: AlertEngine,
    private val notices: PageNotices,
    private val activePage: ActivePageRepository,
    private val sessionKeys: SessionKeys,
    private val rosterScheduler: RosterSyncScheduler,
    private val clock: Clock,
    private val deliveredTimeoutMs: Long = DELIVERED_TIMEOUT_MS,
    private val selfPages: SelfPages = SelfPages(),
    private val hosting: TalkHosting = TalkHosting()
) : SocketPageEvents {
    private val rules = ActivePageRules(engine, sessionKeys, clock)
    private val eventFlow = MutableSharedFlow<PageEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<PageEvent> = eventFlow.asSharedFlow()

    suspend fun dispatch(data: Map<String, String>) {
        try {
            when (data[TYPE]) {
                "page" -> receivePage(data)

                "page_cancel" -> pageCancel(data)

                "page_ack" -> pageAck(data)

                "page_answered" -> pageAnswered(data)

                "page_state" -> pageState(data)

                "roster" -> rosterScheduler.schedule()

                "test" -> notices.test()

                // check_in is milestone 6; an unknown type is from a newer server.
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Only the type: never payloads, keys or names.
            Log.w(TAG, "push ${data[TYPE]} failed: ${e.javaClass.simpleName}")
        }
    }

    // --- page (the receive flow) ---

    @Suppress("ReturnCount", "ComplexCondition")
    private suspend fun receivePage(data: Map<String, String>) {
        val pageId = data[PAGE_ID]
        val level = data[LEVEL]
        val fromUser = data[FROM_USER]
        val envelope = data[ENVELOPE]
        if (pageId == null || level == null || fromUser == null || envelope == null) return drop("MISSING_FIELD")
        // The server re-pushes a page that was not delivered in time. One already held is not alerted twice; its
        // notification is only posted again (every high-priority message must change something visible).
        if (engine.repost(pageId) || sessionKeys.has(pageId)) return
        val self = tokens.deviceId() ?: return drop("NOT_ENROLLED")
        val roster = rosterSync.heldRoster() ?: return drop("NO_ROSTER")
        val keys = try {
            DeviceKeys.load(keyStore)
        } catch (_: DeviceKeysUnavailable) {
            return drop("KEYS_UNAVAILABLE")
        }
        val signed = try {
            SignedObject.fromWire(envelope)
        } catch (_: ProtocolException) {
            return drop("MALFORMED_ENVELOPE")
        }
        when (val result = PageEnvelopes.receive(roster, self, keys, signed, PagePush(pageId, level, fromUser))) {
            is ReceiveResult.Dropped -> drop(result.reason.name)
            is ReceiveResult.Accepted -> accepted(result)
        }
    }

    private suspend fun accepted(result: ReceiveResult.Accepted) {
        val envelope = result.envelope
        val page = IncomingPage(
            envelope.pageId,
            envelope.level,
            result.sender.userId,
            result.sender.displayName,
            envelope.sessionId
        )
        // Held in memory from here, so a re-push arriving while `delivered` is in flight is recognised.
        sessionKeys.put(page.pageId, result.sessionKey)
        sessionKeys.putPeers(page.pageId, setOf(result.sender.id))
        when (deliver(page.pageId)) {
            // Over, not ours, or already answered by another device: drop silently (protocol section 9).
            is DeliveredOutcome.Drop -> sessionKeys.clear(page.pageId)

            // Alerting comes first and never depends on the database: the row is bookkeeping.
            DeliveredOutcome.Ringing, DeliveredOutcome.Unreachable -> {
                engine.start(page)
                storeActive(page, STATE_RINGING)
            }

            DeliveredOutcome.Acknowledged -> {
                engine.start(page, acknowledged = true)
                storeActive(page, STATE_ACKNOWLEDGED)
            }

            DeliveredOutcome.Answered -> {
                notices.answered(page)
                storeActive(page, STATE_ANSWERED)
            }
        }
    }

    /**
     * `delivered` with a short budget so alerting starts within the 5 s target. On a network failure
     * [Outbox.deliver] queues its own retry. A timeout cancels it, and cancellation skips that queueing, so
     * the row is queued here; the retry may repeat a request that did reach the server, which is harmless.
     */
    private suspend fun deliver(pageId: String): DeliveredOutcome {
        val outcome = try {
            withTimeoutOrNull(deliveredTimeoutMs) { outbox.deliver(pageId) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "delivered failed: ${e.javaClass.simpleName}")
            null
        }
        if (outcome != null) return outcome
        // Alert even if the retry cannot be queued (a failing database).
        guarded("queue delivered") { outbox.enqueue(OutboxAction.Delivered(pageId)) }
        return DeliveredOutcome.Unreachable
    }

    /**
     * The repository holds one row, so a second page cannot always be stored. It is stored when nothing is
     * active or the row is replaceable (see [ActivePageRules]); otherwise the earlier page keeps the row, and the
     * new page still rings and has its own notification (its session id stays in the engine, see [AlertEngine.pageOf]).
     * A failing database is logged, never allowed to stop the alert.
     */
    private suspend fun storeActive(page: IncomingPage, state: String) = guarded("store active page") {
        val current = activePage.current()
        // This page was answered while it was being delivered: the answered row must not go back to ringing.
        val answeredHere = current?.pageId == page.pageId && current.state == STATE_ANSWERED
        if (!answeredHere && (current == null || rules.isReplaceable(current, page.pageId, page.urgent))) {
            activePage.start(page.pageId, PageRole.RECEIVER, page.sessionId, page.fromUserId, page.level, state)
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    // --- the other messages ---

    private suspend fun pageCancel(data: Map<String, String>) {
        val pageId = data[PAGE_ID] ?: return
        val reason = data[REASON]?.takeIf { it in CANCEL_REASONS } ?: REASON_CANCELLED
        if (reason == REASON_MISSED) {
            engine.miss(pageId, fallbackName = nameFromActivePage(pageId))
        } else {
            engine.end(pageId)
            if (reason == REASON_ANSWERED_ELSEWHERE) notices.answeredElsewhere(pageId) else notices.cancelled(pageId)
            sessionKeys.clear(pageId)
            guarded("end active page") { activePage.end(pageId) }
        }
        eventFlow.tryEmit(PageEvent.Ended(pageId, reason))
    }

    private suspend fun pageAck(data: Map<String, String>) {
        val pageId = data[PAGE_ID] ?: return
        if (!engine.acknowledgedElsewhere(pageId)) notices.acknowledgedUnknown(pageId)
        guarded("ack state") { activePage.updateState(pageId, STATE_ACKNOWLEDGED) }
    }

    @Suppress("ReturnCount")
    private suspend fun pageAnswered(data: Map<String, String>) {
        val pageId = data[PAGE_ID] ?: return
        if (isOwnPage(pageId)) return
        // A page this phone already gave up on (`failed`) is not answered afterwards.
        if (rowOf(pageId)?.state?.let(ActivePageRules::isSenderTerminal) == true) return
        val name = nameFromActivePage(pageId)
        advanceSenderState(pageId, STATE_ANSWERED)
        if (hosting.isHosting(pageId)) {
            // Already in the session: the high-priority push ends in a visible change of the ongoing notification.
            hosting.requestRefresh(pageId)
        } else {
            notices.senderAnswered(pageId, name)
        }
        eventFlow.tryEmit(PageEvent.Answered(pageId))
    }

    @Suppress("ReturnCount")
    private suspend fun pageState(data: Map<String, String>) {
        val pageId = data[PAGE_ID] ?: return
        val state = data[STATE]?.takeIf { it in SENDER_STATES } ?: return
        if (isOwnPage(pageId)) return
        if (state in SENDER_TERMINAL) {
            // The page is over: name it first, then remove the row so it cannot block the next page.
            if (state != STATE_ENDED) notices.senderState(pageId, state, nameFromActivePage(pageId))
            guarded("end active page") { activePage.end(pageId) }
        } else {
            advanceSenderState(pageId, state)
        }
        eventFlow.tryEmit(PageEvent.State(pageId, state))
    }

    // --- the WebSocket (SocketPageEvents) ---

    @Suppress("ReturnCount")
    override suspend fun socketPageState(pageId: String, state: WsPageState) {
        val s = when (state) {
            WsPageState.DELIVERED -> "delivered"
            WsPageState.ACKNOWLEDGED -> STATE_ACKNOWLEDGED
            WsPageState.NOT_DELIVERED -> "not_delivered"
            WsPageState.ANSWERED -> STATE_ANSWERED
            WsPageState.RINGING, WsPageState.UNKNOWN -> return
        }
        if (isOwnPage(pageId)) return
        val row = rowOf(pageId)
        // WebSocket and FCM can arrive in any order: only a step forward counts.
        if (row != null && (
                ActivePageRules.isSenderTerminal(row.state) ||
                    ActivePageRules.senderRank(s) <= ActivePageRules.senderRank(row.state)
                )
        ) {
            return
        }
        advanceSenderState(pageId, s)
        eventFlow.tryEmit(if (s == STATE_ANSWERED) PageEvent.Answered(pageId) else PageEvent.State(pageId, s))
    }

    override suspend fun sessionEnded(pageId: String, role: PageRole, reason: SessionEndReason) {
        try {
            when {
                reason == SessionEndReason.ENDED_BY_YOU -> localCleanup(pageId)

                role == PageRole.SENDER -> {
                    val state = when (reason) {
                        SessionEndReason.MISSED -> STATE_MISSED
                        SessionEndReason.FAILED -> STATE_FAILED
                        else -> STATE_ENDED
                    }
                    if (state != STATE_ENDED) notices.senderState(pageId, state, nameFromActivePage(pageId))
                    sessionKeys.clear(pageId)
                    guarded("end active page") { activePage.end(pageId) }
                    eventFlow.tryEmit(PageEvent.State(pageId, state))
                }

                reason == SessionEndReason.IDLE -> {
                    localCleanup(pageId)
                    eventFlow.tryEmit(PageEvent.Ended(pageId, REASON_CANCELLED))
                }

                else -> pageCancel(mapOf(PAGE_ID to pageId, REASON to REASON_CANCELLED))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "session end failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun localCleanup(pageId: String) {
        engine.end(pageId)
        notices.clear(pageId)
        sessionKeys.clear(pageId)
        guarded("end active page") { activePage.end(pageId) }
    }

    /** The sender's row only ever moves forward: `page_state` is normal priority and can arrive late. */
    private suspend fun advanceSenderState(pageId: String, state: String) = guarded("sender state") {
        val row = rowOf(pageId)
        if (row != null && !ActivePageRules.isSenderTerminal(row.state) &&
            ActivePageRules.senderRank(state) > ActivePageRules.senderRank(row.state)
        ) {
            activePage.updateState(pageId, state)
        }
    }

    /**
     * A page this phone sent to its own user (the setup Test alert) is also received here, and the row then
     * belongs to the receiver (see [storeActive]). The sender-side pushes for it (`page_state`, `page_answered`)
     * are about the same page seen from the other end: they must neither change the receiver's row nor post a
     * "<you> answered" notification.
     */
    private suspend fun isOwnReceiverPage(pageId: String): Boolean = rowOf(pageId)?.role == PageRole.RECEIVER

    /** Also once the receiver row is gone: [selfPages] remembers what this phone sent to its own user. */
    private suspend fun isOwnPage(pageId: String): Boolean = selfPages.contains(pageId) || isOwnReceiverPage(pageId)

    private suspend fun rowOf(pageId: String): ActivePageEntity? = try {
        activePage.current()?.takeIf { it.pageId == pageId }
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "active page unreadable: ${e.javaClass.simpleName}")
        null
    }

    /** The name of the other person on the active page, from the verified roster. */
    private suspend fun nameFromActivePage(pageId: String): String? {
        val row = rowOf(pageId)
        val roster: Roster? = if (row == null) null else rosterSync.heldRoster()
        return engine.nameOf(pageId) ?: roster?.devices?.firstOrNull { it.userId == row?.peerUserId }?.displayName
    }

    private fun drop(reason: String) {
        // The reason only, never the push's contents.
        Log.w(TAG, "page dropped: $reason")
    }

    companion object {
        private const val TAG = "PushDispatcher"

        /** The `delivered` budget; alerting must start within 5 s of the push (spec, Testing). */
        const val DELIVERED_TIMEOUT_MS = 3_000L
        private const val EVENT_BUFFER = 16
        private const val TYPE = "type"
        private const val PAGE_ID = "page_id"
        private const val LEVEL = "level"
        private const val FROM_USER = "from_user"
        private const val ENVELOPE = "envelope"
        private const val REASON = "reason"
        private const val STATE = "state"
        private const val REASON_CANCELLED = "cancelled"
        private const val REASON_ANSWERED_ELSEWHERE = "answered_elsewhere"
        private const val REASON_MISSED = "missed"
        private val CANCEL_REASONS = setOf(REASON_CANCELLED, REASON_ANSWERED_ELSEWHERE, REASON_MISSED)
        private const val STATE_RINGING = "ringing"
        private const val STATE_ACKNOWLEDGED = "acknowledged"
        private const val STATE_ANSWERED = "answered"
        private const val STATE_ENDED = "ended"
        private const val STATE_FAILED = "failed"
        private const val STATE_MISSED = "missed"
        private val SENDER_TERMINAL = setOf(STATE_MISSED, STATE_FAILED, STATE_ENDED)
        private val SENDER_STATES = setOf(
            "delivered",
            STATE_ACKNOWLEDGED,
            "not_delivered",
            STATE_MISSED,
            STATE_FAILED,
            STATE_ENDED
        )
    }
}
