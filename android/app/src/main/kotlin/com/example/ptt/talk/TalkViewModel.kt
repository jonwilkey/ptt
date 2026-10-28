package com.example.ptt.talk

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.PageNotices
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.OutboxKind
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxAction
import com.example.ptt.outbox.OutboxOutcome
import com.example.ptt.push.PageEvent
import com.example.ptt.push.SessionKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

enum class TalkPhase {
    /** The page is being looked up; nothing is shown yet. */
    OPENING,

    /** This phone sent the page: still the stub. */
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

/** [caller] is the name from the intent or the alert engine, never from push fields; null if nobody knew it. */
data class TalkState(
    val pageId: String = "",
    val caller: String? = null,
    val phase: TalkPhase = TalkPhase.OPENING,
    /** End was pressed and is being carried out: the button is disabled. */
    val ending: Boolean = false
) {
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
@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel
class TalkViewModel @Inject constructor(
    internal val engine: AlertEngine,
    internal val activePage: ActivePageRepository,
    internal val outbox: Outbox,
    internal val sessionKeys: SessionKeys,
    private val notices: PageNotices,
    private val pageEvents: @JvmSuppressWildcards SharedFlow<PageEvent>,
    private val scope: CoroutineScope
) : ViewModel() {
    private val _state = MutableStateFlow(TalkState())
    val state: StateFlow<TalkState> = _state

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
        if (mode == TalkIntents.MODE_OUTGOING) {
            _state.value = TalkState(pageId, caller, TalkPhase.OUTGOING)
            return
        }
        _state.value = TalkState(pageId, caller, TalkPhase.OPENING)
        scope.launch { open(pageId, mode) }
    }

    fun answerPressed() {
        val pageId = _state.value.pageId
        scope.launch { answer(pageId) }
    }

    /** End: the answerer's cancel (protocol section 6), then forget the page and close. */
    fun end() {
        val current = _state.value
        if (current.phase != TalkPhase.ANSWERED || current.ending) return
        _state.update { it.copy(ending = true) }
        scope.launch {
            val pageId = current.pageId
            // The answer may still be on its way to the outbox and the active-page row.
            answerJobs[pageId]?.join()
            guarded("end") { outbox.enqueue(OutboxAction.Cancel(pageId)) }
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
                pageEvents.collect {
                    if (it !is PageEvent.Ended) return@collect
                    val phase = if (it.reason == REASON_ANSWERED_ELSEWHERE) {
                        TalkPhase.ANSWERED_ELSEWHERE
                    } else {
                        TalkPhase.ENDED
                    }
                    over(it.pageId, phase)
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { activePage.active.collect(::rowChanged) }
        }
    }

    /** The row is the source of truth: once it has been the shown page's, its going away or ending ends the screen. */
    private fun rowChanged(row: ActivePageEntity?) {
        val pageId = _state.value.pageId
        val mine = row != null && row.pageId == pageId
        if (mine) seenRowFor = pageId
        val gone = seenRowFor == pageId && !mine
        val ended = row != null && mine && row.state == STATE_ENDED
        if (gone || ended) over(pageId, TalkPhase.ENDED)
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
        const val REASON_ANSWERED_ELSEWHERE = "answered_elsewhere"
        const val STATUS_ALREADY_ANSWERED = 409
    }
}
