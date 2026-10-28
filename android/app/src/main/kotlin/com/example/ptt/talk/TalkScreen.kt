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

/** The receiver's placeholder talk screen for [state]. [onClose] must finish the whole task. */
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

            TalkPhase.OUTGOING -> Outgoing(state)

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

/** The sender's progress is a later PR; this is what the stub showed. */
@Composable
private fun Outgoing(state: TalkState) {
    Text(
        if (state.caller == null) {
            stringResource(R.string.talk_stub_unknown_caller)
        } else {
            stringResource(R.string.talk_stub_caller, state.caller)
        }
    )
    Text(stringResource(R.string.talk_stub_page_id, state.pageId))
}

@Composable
private fun BigButton(@StringRes label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.headlineMedium)
    }
}
