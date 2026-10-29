package com.example.ptt.alert

import android.util.Log
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxOutcome
import com.example.ptt.push.ActivePageRules
import com.example.ptt.push.SessionKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Marks a page this phone sent that the server will never ring as `failed`: it ended at once (`ended` in the
 * 201), was refused for good, or ran out of its send window (ADR 0007). It lives with the app, not with the
 * alert engine (built lazily) or a screen, so it also works in a process that only runs the outbox worker.
 * [SenderWaiting] starts it.
 */
class LostPages(
    private val outbox: Outbox,
    private val activePage: ActivePageRepository,
    private val sessionKeys: SessionKeys
) {
    /** Subscribes before returning: [Outbox.outcomes] does not replay. */
    fun start(scope: CoroutineScope) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            outbox.outcomes.filter { it.kind == OutboxKind.PAGE && it.result.pageLost() }
                .collect { sentPageLost(it.pageId, onlyIfWaiting = it.result is OutboxOutcome.Result.Sent) }
        }
    }

    private fun OutboxOutcome.Result.pageLost() = when (this) {
        is OutboxOutcome.Result.Sent -> pageState == STATE_ENDED
        is OutboxOutcome.Result.Rejected, OutboxOutcome.Result.Expired -> true
        OutboxOutcome.Result.CancelledLocally -> false
    }

    /**
     * The sender row of a lost page says `failed`, so an open or reopened screen shows it and the next page may
     * replace the row; its session key goes. A receiver row of the same page (a Test alert to this phone itself)
     * is the receiver's and stays.
     */
    private suspend fun sentPageLost(pageId: String, onlyIfWaiting: Boolean) {
        try {
            // A late `ended` in the 201 of an idempotent retry (the page already ran) is not a failure.
            val row = activePage.current()?.takeIf { it.pageId == pageId && it.role == PageRole.SENDER }
            val waitingRow = row?.state == ActivePageRules.STATE_SENDING || row?.state == STATE_NOT_DELIVERED
            if (row != null && (waitingRow || !onlyIfWaiting)) {
                sessionKeys.clear(pageId)
                activePage.updateState(pageId, STATE_FAILED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "page lost: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val STATE_FAILED = "failed"
        const val STATE_NOT_DELIVERED = "not_delivered"
        private const val STATE_ENDED = "ended"
        private const val TAG = "LostPages"
    }
}
