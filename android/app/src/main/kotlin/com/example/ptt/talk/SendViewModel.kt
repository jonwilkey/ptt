package com.example.ptt.talk

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.PageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A page to send: [name] is display only (the confirmation and the talk screen), never an identity. */
data class SendRequest(val userId: String, val level: String, val name: String?)

sealed interface SendUi {
    /** Waiting for the unlock prompt's answer; Close gives up. */
    data object Unlocking : SendUi

    /** The send is under way: nothing to ask, and no Close (the page is being stored). */
    data object Working : SendUi

    /** A live page holds the slot; [peerName] is who it is with. */
    data class Confirm(val peerName: String?) : SendUi

    /** Nothing was sent; the screen says why and offers Close. */
    data class Failed(@StringRes val res: Int, val name: String?) : SendUi
}

sealed interface SendEvent {
    /** The talk screen was started already; this screen is done. */
    data class Open(val pageId: String, val name: String?) : SendEvent

    /** Close the screen without opening anything. */
    data object Close : SendEvent
}

/**
 * The send entry point's logic (spec "Sending a page", "Lock screen"). One instance serves one [SendActivity]
 * across rotation; the send runs on the application scope like the Family screen's. A page is sent only through
 * [run] (after the unlock) or [confirmEnd] (after the user agreed), each guarded so a repeat intent, a second
 * callback or a recreation can never send twice.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class SendViewModel @Inject constructor(
    private val sender: PageSender,
    private val activePage: ActivePageRepository,
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) : ViewModel() {
    private val _ui = MutableStateFlow<SendUi>(SendUi.Unlocking)
    val ui: StateFlow<SendUi> = _ui

    private val channel = Channel<SendEvent>(Channel.BUFFERED)
    val events: Flow<SendEvent> = channel.receiveAsFlow()

    private var request: SendRequest? = null
    private var inFlight = false
    private var finished = false
    private var unlockToken = 0

    /** Whether any request was ever accepted; false on a fresh view model after process death. */
    @get:Synchronized
    val started: Boolean get() = request != null

    /**
     * Takes [new] as the request to carry out, returning whether the caller should now get the unlock and
     * call [run]. A repeat of the request being waited on or asked about, anything while a send is under way,
     * and anything after the screen has finished, are ignored.
     */
    @Synchronized
    fun accept(new: SendRequest): Boolean {
        val repeat = new == request && _ui.value !is SendUi.Failed
        val accepted = !finished && !inFlight && !repeat
        if (accepted) {
            request = new
            _ui.value = SendUi.Unlocking
        }
        return accepted
    }

    /**
     * Numbers an unlock prompt for the request just accepted. A newer request's prompt cancels the older one
     * in the system, and that cancel must not close the screen: only the latest prompt's answer counts.
     */
    @Synchronized
    fun newUnlock(): Int = ++unlockToken

    /** The answer of the unlock prompt numbered [token]: send on success, close otherwise; stale ones are ignored. */
    fun unlocked(token: Int, success: Boolean) {
        synchronized(this) { if (token != unlockToken) return }
        if (success) run() else cancel()
    }

    /** The phone is unlocked: send the accepted request, asking first if a page of another person or level is live. */
    fun run() = send(endCurrent = false)

    /** The user agreed to end the current page for the request. */
    fun confirmEnd() = send(endCurrent = true)

    /** Cancel, or the unlock failed: nothing is sent and any current page is left alone. */
    @Synchronized
    fun cancel() {
        if (finished || inFlight) return
        finish(SendEvent.Close)
    }

    @Synchronized
    private fun send(endCurrent: Boolean) {
        val req = request ?: return
        val ui = _ui.value
        // run() only while waiting for the unlock; confirmEnd() only from the question.
        val ready = if (endCurrent) ui is SendUi.Confirm else ui is SendUi.Unlocking
        if (finished || inFlight || !ready) return
        inFlight = true
        _ui.value = SendUi.Working
        scope.launch {
            val next = outcome(req, endCurrent)
            synchronized(this@SendViewModel) {
                inFlight = false
                when (next) {
                    is SendOutcome.Open -> open(next.pageId, req.name)
                    is SendOutcome.Ask -> _ui.value = SendUi.Confirm(next.peerName)
                    is SendOutcome.Message -> _ui.value = SendUi.Failed(next.res, next.name)
                }
            }
        }
    }

    private suspend fun outcome(req: SendRequest, endCurrent: Boolean): SendOutcome {
        val result = sender.attempt(req.userId, req.name, req.level, endCurrent)
        if (result !is SendOutcome.Ask) return result
        // The same person at the same level, sent from this phone: that page is the one asked for.
        val row = activePage.current()
        return if (row != null && row.isSentTo(req)) {
            SendOutcome.Open(row.pageId)
        } else {
            result
        }
    }

    /**
     * Opens the talk screen from the application context, not through the activity: with Back pressed during
     * the send the activity may be gone, and the page would be sent with nobody shown it.
     */
    private fun open(pageId: String, name: String?) {
        context.startActivity(TalkIntents.intent(context, pageId, TalkIntents.MODE_OUTGOING, name))
        finish(SendEvent.Open(pageId, name))
    }

    private fun ActivePageEntity.isSentTo(req: SendRequest) =
        role == PageRole.SENDER && peerUserId == req.userId && level == req.level

    private fun finish(event: SendEvent) {
        finished = true
        channel.trySend(event)
    }
}
