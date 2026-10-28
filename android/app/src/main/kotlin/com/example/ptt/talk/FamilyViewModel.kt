package com.example.ptt.talk

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.example.ptt.R
import com.example.ptt.data.RosterRepository
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.setup.RosterView
import com.example.ptt.setup.toRoster
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One person of the family (not one phone) that can be paged. */
data class FamilyPerson(val userId: String, val name: String)

/** The question asked before a live page is ended for a new one. */
data class FamilyConfirm(val person: FamilyPerson, val level: String, val currentName: String?)

sealed interface FamilyEvent {
    /** The page was stored and queued: open the sender's screen for it. */
    data class Open(val pageId: String, val name: String) : FamilyEvent

    /** Nothing was sent; [name] fills the message's `%1$s`, if it has one. */
    data class Message(@StringRes val res: Int, val name: String? = null) : FamilyEvent
}

/**
 * The Family screen: everyone in the verified roster except this phone's own user, each with Page and Urgent.
 * Sending runs on the application scope, so leaving the screen never abandons a page half-stored.
 */
@HiltViewModel
class FamilyViewModel @Inject constructor(
    rosters: RosterRepository,
    tokens: DeviceTokenStore,
    private val sender: PageSender,
    private val scope: CoroutineScope
) : ViewModel() {
    /** Null while there is no readable verified roster: that is not an empty family. */
    val people: Flow<List<FamilyPerson>?> = rosters.roster.map { entity ->
        val view = entity.toRoster()?.let { RosterView(it, tokens.deviceId()) } ?: return@map null
        val self = view.roster.find(view.selfId.orEmpty())?.userId
        view.people.filter { it.userId != self }.map { FamilyPerson(it.userId, it.displayName) }
    }

    private val _confirm = MutableStateFlow<FamilyConfirm?>(null)
    val confirm: StateFlow<FamilyConfirm?> = _confirm

    private val eventChannel = Channel<FamilyEvent>(Channel.BUFFERED)
    val events: Flow<FamilyEvent> = eventChannel.receiveAsFlow()

    private val sending = AtomicBoolean(false)

    /** A tap on Page or Urgent. With a live page in the slot this asks first; see [confirmEnd]. */
    fun page(person: FamilyPerson, level: String) = send(person, level, endCurrent = false)

    /** The user agreed to end the current page for the one in [confirm]. */
    fun confirmEnd() {
        val pending = _confirm.value ?: return
        _confirm.value = null
        send(pending.person, pending.level, endCurrent = true)
    }

    fun dismissConfirm() {
        _confirm.value = null
    }

    private fun send(person: FamilyPerson, level: String, endCurrent: Boolean) {
        // A second tap while the first is being stored would find the first page and ask to end it.
        if (!sending.compareAndSet(false, true)) return
        scope.launch {
            try {
                when (val result = sender.send(person.userId, level, endCurrent)) {
                    is SendResult.Sent -> eventChannel.send(FamilyEvent.Open(result.pageId, person.name))

                    is SendResult.Busy -> _confirm.value = FamilyConfirm(person, level, result.peerName)

                    SendResult.NoDevices -> eventChannel.send(
                        FamilyEvent.Message(R.string.family_no_phones, person.name)
                    )

                    else -> eventChannel.send(FamilyEvent.Message(result.failureMessage()))
                }
            } finally {
                sending.set(false)
            }
        }
    }
}

/** The line for a result that sent nothing and is not a question; the callers handle `Sent`, `Busy` and `NoDevices`. */
@StringRes
internal fun SendResult.failureMessage(): Int = when (this) {
    SendResult.KeysUnavailable -> R.string.send_keys_unavailable
    SendResult.Error -> R.string.send_error
    else -> R.string.send_not_ready
}
