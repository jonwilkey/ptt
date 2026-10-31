package com.example.ptt.talk

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.PageNotices
import com.example.ptt.audio.CaptureStatus
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.net.SessionEndReason
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxAction
import com.example.ptt.outbox.OutboxOutcome
import com.example.ptt.push.PageEvent
import com.example.ptt.push.SessionKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

enum class TalkPhase {
    /** The page is being looked up; nothing is shown yet. */
    OPENING,

    /** This phone sent the page: [TalkState.progress] says how far it got. */
    OUTGOING,

    /** The urgent full-screen intent: waiting for the Answer button. */
    INCOMING,

    /** This phone answered; the placeholder with its End button. */
    ANSWERED,

    /** The page is over (cancelled, missed, or ended): shown for a moment, then the screen closes. */
    ENDED,

    /** Another phone answered first. Same as [ENDED] otherwise. */
    ANSWERED_ELSEWHERE,

    /** End was pressed and finished: the screen closes now. */
    DONE
}

/** How far a page this phone sent got (spec "Sending a page"), shown on the sender's screen. */
enum class SendProgress(val terminal: Boolean = false) {
    SENDING,
    NOT_DELIVERED,
    DELIVERED,
    ACKNOWLEDGED,
    ANSWERED,

    /** The page timed out unanswered (`missed`). */
    NO_ANSWER(terminal = true),

    /** The server never rang anyone: it ended at once, refused the page, or the send window ran out. */
    FAILED(terminal = true);

    /** Whether a screen at [this] moves to [next]: only forward, except that a final state always wins. */
    fun allows(next: SendProgress) = !terminal && (next.terminal || next.ordinal > ordinal)

    companion object {
        /** The progress a page state stands for; null for `ended` and for states this build does not know. */
        fun of(state: String): SendProgress? = when (state) {
            "sending" -> SENDING
            "not_delivered" -> NOT_DELIVERED
            "delivered" -> DELIVERED
            "acknowledged" -> ACKNOWLEDGED
            "answered" -> ANSWERED
            "missed" -> NO_ANSWER
            "failed" -> FAILED
            else -> null
        }
    }
}

/**
 * [caller] is the name from the intent or the alert engine, never from push fields; null if nobody knew it. For a
 * page this phone sent ([sender]) it is the receiver's name, [progress] the sender's state and [urgent] the level.
 */
data class TalkState(
    val pageId: String = "",
    val caller: String? = null,
    val phase: TalkPhase = TalkPhase.OPENING,
    /** End was pressed and is being carried out: the button is disabled. */
    val ending: Boolean = false,
    val sender: Boolean = false,
    val progress: SendProgress = SendProgress.SENDING,
    val urgent: Boolean = false,
    /** RECORD_AUDIO is not granted, so live talk cannot start: the setup checklist has the item. */
    val microphoneMissing: Boolean = false,
    /** Why the live session ended (from the WebSocket `session_end`), for the line shown while the screen closes. */
    val endReason: SessionEndReason? = null
) {
    /** Whether this screen's page should have the live-talk service running (a sender waiting, or an answered page). */
    val wantsLiveTalk: Boolean
        get() = pageId.isNotEmpty() &&
            (phase == TalkPhase.ANSWERED || (sender && phase == TalkPhase.OUTGOING && !progress.terminal))

    val over: Boolean get() = phase == TalkPhase.ENDED || phase == TalkPhase.ANSWERED_ELSEWHERE
}

/**
 * What the receiver's talk screen does. The work that must not be lost when the screen closes (answering,
 * ending) runs on the application scope; only the watching of the page's fate lives and dies with the screen.
 *
 * Answering is idempotent (see [AlertEngine.answer]) and also guarded per page here, so recreating the
 * activity, tapping the notification again, or the Answer button after a notification tap enqueue one
 * `answer` between them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
@HiltViewModel
class TalkViewModel @Inject constructor(
    internal val engine: AlertEngine,
    internal val activePage: ActivePageRepository,
    internal val outbox: Outbox,
    internal val sessionKeys: SessionKeys,
    private val notices: PageNotices,
    private val pageEvents: @JvmSuppressWildcards SharedFlow<PageEvent>,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val talk: TalkSessions,
    private val audio: TalkAudioSource
) : ViewModel() {
    private val _state = MutableStateFlow(TalkState())
    val state: StateFlow<TalkState> = _state

    private val holdToTalk = HoldToTalk(audio)

    /**
     * The live-talk part of the screen. Eagerly shared so that the talk-disabled release below runs whether or
     * not the UI is composed.
     */
    val live: StateFlow<TalkLive> = combine(
        _state,
        talk.state,
        _state.map { it.pageId }.distinctUntilChanged().flatMapLatest { audio.captureStatus(it) }
    ) { screen, session, capture ->
        deriveLive(
            screen,
            if (ours(session)) session else TalkSessionState(),
            eligible(screen),
            capture
        )
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TalkLive())

    /** The one-shot events, for the chirps and the busy buzz (the next PR). Durable UI never reads these. */
    val talkEvents: SharedFlow<TalkEvent> get() = talk.events

    private val answered: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The coroutine answering each page, so that End can wait for it: a cancel must never overtake the answer. */
    private val answerJobs = ConcurrentHashMap<String, Job>()

    /** The page whose active-page row has been seen while it was shown: before that its absence means nothing. */
    @Volatile
    private var seenRowFor: String? = null

    /**
     * The screen was opened (or got a new intent) for [pageId]. The same page again changes nothing, except
     * that a notification tap ([TalkIntents.MODE_ANSWER]) on a page whose Answer button is showing answers it.
     * A different page replaces the screen's page; the earlier one is left to the alert logic.
     */
    fun show(pageId: String, mode: String, callerName: String?) {
        if (_state.value.pageId == pageId) {
            if (mode == TalkIntents.MODE_ANSWER) scope.launch { answer(pageId) }
            return
        }
        val caller = callerName ?: engine.nameOf(pageId)
        seenRowFor = null
        holdToTalk.release()
        if (mode == TalkIntents.MODE_OUTGOING) {
            _state.value = TalkState(pageId, caller, TalkPhase.OUTGOING, sender = true)
            scope.launch { openOutgoing(pageId) }
            return
        }
        _state.value = TalkState(pageId, caller, TalkPhase.OPENING)
        scope.launch { open(pageId, mode) }
    }

    /**
     * The hold-to-talk button went down. Does nothing (and never touches the microphone) unless talk is enabled for
     * the shown page. True if a floor request was made; false is "no request", not an error.
     */
    fun pressTalk(): Boolean {
        val shown = _state.value.pageId
        return holdToTalk.press(shown, live.value.talkEnabled)
    }

    /** The button went up or was cancelled, the window lost focus, or the screen stopped. Idempotent. */
    fun releaseTalk() = holdToTalk.release()

    override fun onCleared() {
        holdToTalk.release()
    }

    /** Whether live talk may run for the shown page (never for a Test alert to this phone itself). */
    fun liveTalkEligible(): Boolean = talk.eligible(_state.value.pageId)

    private fun eligible(screen: TalkState) = screen.pageId.isNotEmpty() && talk.eligible(screen.pageId)

    fun microphoneMissing(missing: Boolean) = _state.update { it.copy(microphoneMissing = missing) }

    fun answerPressed() {
        val pageId = _state.value.pageId
        scope.launch { answer(pageId) }
    }

    /**
     * End: the cancel (protocol section 6), then forget the page and close. The sender may press it at any time:
     * a page still waiting in the outbox collapses there without a request (ADR 0007), and one that is already
     * lost (no answer, failed) needs no cancel, only the clean-up.
     */
    fun end() {
        val current = _state.value
        val canEnd = current.phase == TalkPhase.ANSWERED || (current.sender && current.phase == TalkPhase.OUTGOING)
        if (!canEnd || current.ending) return
        holdToTalk.release()
        _state.update { it.copy(ending = true) }
        scope.launch {
            val pageId = current.pageId
            // The answer may still be on its way to the outbox and the active-page row.
            answerJobs[pageId]?.join()
            // The WebSocket session_end (when joined) ends the peer's session at once, but a half-dead socket can lose
            // it: the REST cancel through the outbox is always queued too. Both are idempotent; a 410 is harmless
            // (cancel outcomes are not watched here).
            guarded("end session") { talk.requestEnd(pageId) }
            if (!current.progress.terminal) guarded("end") { outbox.enqueue(OutboxAction.Cancel(pageId)) }
            engine.end(pageId)
            notices.clear(pageId)
            sessionKeys.clear(pageId)
            guarded("end active page") { activePage.end(pageId) }
            moveTo(pageId, TalkPhase.DONE)
        }
    }

    private suspend fun open(pageId: String, mode: String) {
        val row = readRow(pageId)
        // Another page may have replaced this one while the row was being read.
        if (_state.value.pageId != pageId) return
        // The row exists already: the collector will not emit it again, so its going away must still count.
        if (row != null) seenRowFor = pageId
        when {
            row?.state == STATE_ENDED -> over(pageId, TalkPhase.ENDED)

            // Also when the row says answered: the engine call then only clears the notification.
            row?.state == STATE_ANSWERED || mode == TalkIntents.MODE_ANSWER -> answer(pageId)

            else -> moveTo(pageId, TalkPhase.INCOMING)
        }
    }

    /** The sender's screen: the row says where the page is; none means it is over (or was never ours). */
    private suspend fun openOutgoing(pageId: String) {
        val row = readRow(pageId)
        if (_state.value.pageId != pageId) return
        if (row == null) {
            over(pageId, TalkPhase.ENDED)
        } else {
            seenRowFor = pageId
            senderRow(row)
        }
    }

    private suspend fun answer(pageId: String) {
        val current = _state.value
        if (current.pageId != pageId || current.over || current.phase == TalkPhase.DONE) return
        if (answered.add(pageId)) {
            answerJobs[pageId] = currentCoroutineContext().job
            moveTo(pageId, TalkPhase.ANSWERED)
            engine.answer(pageId)
        } else {
            moveTo(pageId, TalkPhase.ANSWERED)
        }
    }

    // Watches for the shown page ending, for the life of the screen: a final answer outcome (409 already
    // answered, 410 page ended), the dispatcher's `page_cancel`, and the active-page row going away. The
    // collectors are subscribed before the constructor returns, because Outbox.outcomes does not replay and
    // the answer is sent at once.
    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                outbox.outcomes.filter { it.kind == OutboxKind.ANSWER }.collect { answerOutcome(it.pageId, it.result) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                outbox.outcomes.filter { it.kind == OutboxKind.PAGE }.collect { pageOutcome(it.pageId, it.result) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                pageEvents.collect {
                    when (it) {
                        is PageEvent.State -> senderState(it.pageId, it.state)

                        is PageEvent.Answered -> senderProgress(it.pageId, SendProgress.ANSWERED)

                        is PageEvent.Ended -> over(
                            it.pageId,
                            if (it.reason ==
                                REASON_ANSWERED_ELSEWHERE
                            ) {
                                TalkPhase.ANSWERED_ELSEWHERE
                            } else {
                                TalkPhase.ENDED
                            }
                        )
                    }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { activePage.active.collect(::rowChanged) }
            launch(start = CoroutineStart.UNDISPATCHED) { talk.events.collect(::sessionEvent) }
            launch(start = CoroutineStart.UNDISPATCHED) {
                live.map { it.talkEnabled }.distinctUntilChanged().collect { if (!it) holdToTalk.release() }
            }
        }
    }

    /** True if [session] is the live session of the shown page (the controller's state can be stale for another). */
    private fun ours(session: TalkSessionState) = session.pageId != null && session.pageId == _state.value.pageId

    private fun sessionEvent(event: TalkEvent) {
        if (event is TalkEvent.SessionEnded && ours(talk.state.value)) sessionEnded(event.reason)
    }

    /**
     * The live session ended. Our own End is handled by [end]; a peer's end or the idle timeout normally also
     * arrives as the page ending, but make sure the screen cannot hang open if that is late. A sender's
     * `missed`/`failed` is shown as its progress instead.
     */
    private fun sessionEnded(reason: SessionEndReason) {
        val pageId = _state.value.pageId
        if (reason == SessionEndReason.ENDED_BY_YOU) return
        val keepsProgress = reason == SessionEndReason.MISSED || reason == SessionEndReason.FAILED
        _state.update { if (it.pageId == pageId && !it.ending) it.copy(endReason = reason) else it }
        if (!keepsProgress) over(pageId, TalkPhase.ENDED)
    }

    /** The row is the source of truth: once it has been the shown page's, its going away or ending ends the screen. */
    private fun rowChanged(row: ActivePageEntity?) {
        if (_state.value.sender) {
            senderRowChanged(row)
            return
        }
        val pageId = _state.value.pageId
        val mine = row != null && row.pageId == pageId
        if (mine) seenRowFor = pageId
        val gone = seenRowFor == pageId && !mine
        val ended = row != null && mine && row.state == STATE_ENDED
        if (gone || ended) over(pageId, TalkPhase.ENDED)
    }

    private fun senderRowChanged(row: ActivePageEntity?) {
        val pageId = _state.value.pageId
        val mine = row?.takeIf { it.pageId == pageId }
        when {
            // A page to this phone's own user: the receiver row is the ringing phone's, not this screen's.
            mine != null && mine.role != PageRole.SENDER -> Unit

            mine != null -> {
                seenRowFor = pageId
                senderRow(mine)
            }

            // The dispatcher deletes the row when the page is over; its state event, which may follow, says why.
            seenRowFor == pageId -> senderOver(pageId)
        }
    }

    private fun senderRow(row: ActivePageEntity) {
        val urgent = row.level == EnvelopePayload.LEVEL_URGENT
        _state.update { if (it.pageId == row.pageId) it.copy(urgent = urgent) else it }
        // A page still `sending` past its send window was dropped by an outbox pass that no engine saw (a fresh
        // WorkManager process): it will never ring.
        val overdue = row.state == STATE_SENDING && clock.millis() - row.createdAt > Outbox.PAGE_SEND_WINDOW_MS
        if (overdue) senderProgress(row.pageId, SendProgress.FAILED) else senderState(row.pageId, row.state)
    }

    private fun senderState(pageId: String, state: String) {
        val progress = SendProgress.of(state)
        if (progress == null) {
            if (state == STATE_ENDED) senderOver(pageId)
        } else {
            senderProgress(pageId, progress)
        }
    }

    /** The sender's page is over; a final progress already shown (no answer, failed) says more than "ended". */
    private fun senderOver(pageId: String) {
        val current = _state.value
        if (!current.progress.terminal || current.pageId != pageId) over(pageId, TalkPhase.ENDED)
    }

    /**
     * The server's word on a page this phone sent: forward only. A final state also corrects a screen that
     * guessed "ended" from the row going away.
     */
    private fun senderProgress(pageId: String, progress: SendProgress, onlyWhileWaiting: Boolean = false) =
        _state.update {
            val ours = it.pageId == pageId && it.sender && !it.ending && it.phase != TalkPhase.DONE
            val waiting = it.phase == TalkPhase.OUTGOING && it.progress <= SendProgress.NOT_DELIVERED
            when {
                !ours || (onlyWhileWaiting && !waiting) -> it

                it.phase == TalkPhase.ENDED && progress.terminal -> it.copy(
                    phase = TalkPhase.OUTGOING,
                    progress = progress
                )

                it.phase == TalkPhase.OUTGOING && it.progress.allows(progress) -> it.copy(progress = progress)

                else -> it
            }
        }

    /** A page this phone sent that the server will never ring (the `ended` in its 201, a refusal, the send window). */
    private fun pageOutcome(pageId: String, result: OutboxOutcome.Result) {
        when (result) {
            // A late `ended` in the 201 of an idempotent retry (the page already ran) is not a failure.
            is OutboxOutcome.Result.Sent -> if (result.pageState == STATE_ENDED) {
                senderProgress(pageId, SendProgress.FAILED, onlyWhileWaiting = true)
            }

            is OutboxOutcome.Result.Rejected, OutboxOutcome.Result.Expired -> senderProgress(
                pageId,
                SendProgress.FAILED
            )

            OutboxOutcome.Result.CancelledLocally -> Unit
        }
    }

    /** Any final refusal of our answer means the page is over for us; the engine forgets its row and key. */
    private fun answerOutcome(pageId: String, result: OutboxOutcome.Result) {
        val rejected = result as? OutboxOutcome.Result.Rejected ?: return
        over(pageId, if (rejected.status == STATUS_ALREADY_ANSWERED) TalkPhase.ANSWERED_ELSEWHERE else TalkPhase.ENDED)
    }

    private suspend fun readRow(pageId: String) = try {
        activePage.current()?.takeIf { it.pageId == pageId }
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "active page unreadable: ${e.javaClass.simpleName}")
        null
    }

    /** Moves [pageId]'s screen to [phase] unless the page is no longer shown or is already over or done. */
    private fun moveTo(pageId: String, phase: TalkPhase) = _state.update {
        if (it.pageId != pageId || it.over || it.phase == TalkPhase.DONE) it else it.copy(phase = phase)
    }

    /** The page is over. Our own End is not "over"; and "answered elsewhere" is more precise than "ended". */
    private fun over(pageId: String, phase: TalkPhase) = _state.update {
        val settled = it.phase == TalkPhase.DONE || it.ending
        val refinable = it.phase == TalkPhase.ENDED && phase == TalkPhase.ANSWERED_ELSEWHERE
        val ignored = it.pageId != pageId || settled
        if (ignored || (it.over && !refinable)) it else it.copy(phase = phase)
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

    private companion object {
        const val TAG = "TalkViewModel"
        const val STATE_ANSWERED = "answered"
        const val STATE_ENDED = "ended"
        const val STATE_SENDING = "sending"
        const val REASON_ANSWERED_ELSEWHERE = "answered_elsewhere"
        const val STATUS_ALREADY_ANSWERED = 409
    }
}
