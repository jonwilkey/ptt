package com.example.ptt.talk

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ptt.R
import kotlinx.coroutines.delay

/** How long the "page ended" line stays up before the screen closes itself. */
const val OVER_CLOSE_DELAY_MS = 2_000L

/**
 * The placeholder talk screen (live talk is milestone 4) for [state], either side of a page. [onClose] must
 * finish the whole task.
 */
@Composable
fun TalkScreen(state: TalkState, onAnswer: () -> Unit, onEnd: () -> Unit, onClose: () -> Unit) {
    LaunchedEffect(state.phase) {
        if (state.over) delay(OVER_CLOSE_DELAY_MS)
        if (state.over || state.phase == TalkPhase.DONE) onClose()
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (state.phase) {
            TalkPhase.OPENING, TalkPhase.DONE -> Unit

            TalkPhase.OUTGOING -> Outgoing(state, onEnd)

            TalkPhase.INCOMING -> {
                Text(stringResource(R.string.talk_urgent_page), style = MaterialTheme.typography.titleLarge)
                Caller(state)
                BigButton(R.string.talk_answer, onAnswer)
            }

            TalkPhase.ANSWERED -> {
                Caller(state)
                Text(stringResource(R.string.talk_answered), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.talk_live_unavailable))
                BigButton(R.string.talk_end, onEnd, enabled = !state.ending)
            }

            TalkPhase.ENDED -> Text(
                stringResource(R.string.talk_page_ended),
                style = MaterialTheme.typography.titleLarge
            )

            TalkPhase.ANSWERED_ELSEWHERE ->
                Text(stringResource(R.string.talk_answered_elsewhere), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun Caller(state: TalkState) {
    Text(state.caller ?: stringResource(R.string.unknown_caller), style = MaterialTheme.typography.headlineLarge)
}

/** The sender's progress; End is there at any time, and closes the screen once the page is lost anyway. */
@Composable
private fun Outgoing(state: TalkState, onEnd: () -> Unit) {
    val name = state.caller ?: stringResource(R.string.unknown_caller)
    Text(
        stringResource(if (state.urgent) R.string.talk_outgoing_urgent else R.string.talk_outgoing, name),
        style = MaterialTheme.typography.headlineLarge
    )
    val progress = state.progress
    Text(stringResource(progress.label()), style = MaterialTheme.typography.titleLarge)
    if (progress == SendProgress.ANSWERED) Text(stringResource(R.string.talk_live_unavailable))
    BigButton(if (progress.terminal) R.string.talk_close else R.string.talk_end, onEnd, enabled = !state.ending)
}

@StringRes
private fun SendProgress.label(): Int = when (this) {
    SendProgress.SENDING -> R.string.talk_sending
    SendProgress.NOT_DELIVERED -> R.string.talk_not_delivered
    SendProgress.DELIVERED -> R.string.talk_delivered
    SendProgress.ACKNOWLEDGED -> R.string.talk_acknowledged
    SendProgress.ANSWERED -> R.string.talk_answered
    SendProgress.NO_ANSWER -> R.string.talk_no_answer
    SendProgress.FAILED -> R.string.talk_failed
}

@Composable
private fun BigButton(@StringRes label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.headlineMedium)
    }
}
