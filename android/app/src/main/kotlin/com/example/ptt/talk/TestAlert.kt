package com.example.ptt.talk

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.example.ptt.R
import com.example.ptt.crypto.EnvelopePayload
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [secondsLeft] is set during the countdown; [message] is the result of the last test, if any. */
data class TestAlertState(val secondsLeft: Int? = null, @StringRes val message: Int? = null)

/**
 * The setup checklist's Test alert (spec "Permissions and setup checklist"): after a countdown, during which the
 * user locks the phone, a page goes through the server to the user's own phones, this one included, so the real
 * receive, ring and answer path is tested. The countdown runs on the application scope: locking the phone or
 * leaving the screen must not cancel it; only [cancel] does. The sender's screen is not opened for it.
 */
@HiltViewModel
class TestAlertViewModel @Inject constructor(private val sender: PageSender, private val scope: CoroutineScope) :
    ViewModel() {
    private val _state = MutableStateFlow(TestAlertState())
    val state: StateFlow<TestAlertState> = _state

    private var job: Job? = null

    /** Starts the countdown for a page of [level]; ignored while one is running. */
    @Synchronized
    fun start(level: String) {
        if (job?.isActive == true) return
        _state.value = TestAlertState(secondsLeft = COUNTDOWN_S)
        job = scope.launch {
            for (left in COUNTDOWN_S downTo 1) {
                _state.value = TestAlertState(secondsLeft = left)
                delay(TICK_MS)
            }
            _state.value = TestAlertState(message = messageFor(sender.sendToSelf(level)))
        }
    }

    /** Cancels a countdown that is still running; nothing is sent. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = TestAlertState()
    }

    @StringRes
    private fun messageFor(result: SendResult): Int = when (result) {
        is SendResult.Sent -> R.string.test_alert_sent

        // The user has locked the phone by now: a page in the slot is not ended for a test.
        is SendResult.Busy -> R.string.test_alert_busy

        else -> result.failureMessage()
    }

    companion object {
        const val COUNTDOWN_S = 10
        const val TICK_MS = 1_000L
    }
}

@Composable
fun TestAlertSection(state: TestAlertState, onStart: (String) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.test_alert_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.test_alert_intro))
        if (state.secondsLeft != null) {
            Text(
                stringResource(R.string.test_alert_countdown, state.secondsLeft),
                style = MaterialTheme.typography.titleLarge
            )
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.test_alert_cancel)) }
        } else {
            OutlinedButton(onClick = { onStart(EnvelopePayload.LEVEL_NORMAL) }) {
                Text(stringResource(R.string.test_alert_normal))
            }
            OutlinedButton(onClick = { onStart(EnvelopePayload.LEVEL_URGENT) }) {
                Text(stringResource(R.string.test_alert_urgent))
            }
            state.message?.let { Text(stringResource(it)) }
        }
    }
}
