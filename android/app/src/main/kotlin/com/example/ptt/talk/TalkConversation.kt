package com.example.ptt.talk

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ptt.R
import com.example.ptt.audio.CaptureStatus

/** The live conversation: who is talking, how the connection is, the hold-to-talk button and End. */
@Composable
internal fun Conversation(
    state: TalkState,
    live: TalkLive,
    onEnd: () -> Unit,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val who = name(state)
    Text(who, style = MaterialTheme.typography.headlineLarge)
    Text(stringResource(R.string.talk_answered), style = MaterialTheme.typography.titleMedium)
    connectionLine(live)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
    when (live.speaker) {
        Speaker.YOU -> Text(stringResource(R.string.talk_you_talking), style = MaterialTheme.typography.titleLarge)

        Speaker.PEER -> Text(
            stringResource(R.string.talk_peer_talking, who),
            style = MaterialTheme.typography.titleLarge
        )

        Speaker.NONE -> Unit
    }
    // With talk enabled the peer being away does not stop anyone talking (protocol 2): it plays when they are back.
    if (live.waitingForPeer) {
        Text(stringResource(if (live.talkEnabled) R.string.talk_peer_away else R.string.talk_waiting_for, who))
    }
    if (live.firstTalkerLocked) Text(stringResource(R.string.talk_speaks_first, who))
    captureLine(live.capture)?.let { Text(stringResource(it)) }
    ownLine(live.ownNotice, who)?.let { Text(it) }
    if (state.microphoneMissing) Text(stringResource(R.string.talk_microphone_missing))
    HoldToTalkButton(live.talkEnabled, live.speaker == Speaker.YOU, onPress, onRelease)
    BigButton(R.string.talk_end, onEnd, enabled = !state.ending)
}

@Composable
private fun connectionLine(live: TalkLive): String? = when (live.connection) {
    TalkConnection.IDLE -> null

    TalkConnection.CONNECTING -> stringResource(R.string.talk_connecting)

    TalkConnection.UNAVAILABLE -> stringResource(R.string.talk_live_talk_unavailable)

    TalkConnection.LIVE -> {
        val mode = if (live.clipMode) R.string.talk_clip_mode else R.string.talk_live
        when (live.quality) {
            null -> stringResource(mode)

            else -> stringResource(
                R.string.talk_mode_quality,
                stringResource(mode),
                stringResource(live.quality.label())
            )
        }
    }
}

@StringRes
private fun LinkQuality.label(): Int = when (this) {
    LinkQuality.GOOD -> R.string.talk_quality_good
    LinkQuality.FAIR -> R.string.talk_quality_fair
    LinkQuality.POOR -> R.string.talk_quality_poor
}

@Composable
private fun ownLine(notice: OwnNotice, who: String): String? = when (notice) {
    OwnNotice.NONE -> null
    OwnNotice.NOT_HEARD_LIVE -> stringResource(R.string.talk_not_heard_live, who)
    OwnNotice.SENDING_AS_CLIP -> stringResource(R.string.talk_sending_as_clip)
    OwnNotice.NOT_SENT -> stringResource(R.string.talk_not_sent)
}

@StringRes
private fun captureLine(status: CaptureStatus): Int? = when (status) {
    CaptureStatus.IDLE, CaptureStatus.CAPTURING -> null
    CaptureStatus.STOPPED_AT_CAP -> R.string.talk_capture_cap
    CaptureStatus.MIC_UNAVAILABLE -> R.string.talk_capture_mic
    CaptureStatus.CODEC_UNAVAILABLE -> R.string.talk_capture_codec
    CaptureStatus.FOCUS_LOST -> R.string.talk_capture_focus
    CaptureStatus.REFUSED -> R.string.talk_capture_refused
}

/**
 * The big hold-to-talk button. [onPress] fires on pointer down (only while [enabled]); [onRelease] on up, on
 * cancel, when the pointer handling is torn down, and when the button leaves the composition. The hold lives in
 * the pointer-input coroutine, not in recomposition; the owner also releases when talk becomes disabled.
 */
@Composable
internal fun HoldToTalkButton(enabled: Boolean, talking: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    DisposableEffect(Unit) { onDispose { currentRelease() } }
    val colors = MaterialTheme.colorScheme
    val background = when {
        !enabled -> colors.surfaceVariant
        talking -> colors.tertiary
        else -> colors.primary
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .background(background, MaterialTheme.shapes.extraLarge)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (currentEnabled) {
                        currentPress()
                        try {
                            // Null means the gesture was cancelled; either way the hold is over.
                            waitForUpOrCancellation()
                        } finally {
                            currentRelease()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(if (talking) R.string.talk_talking else R.string.talk_hold_to_talk),
            style = MaterialTheme.typography.headlineMedium,
            color = if (enabled) colors.onPrimary else colors.onSurfaceVariant
        )
    }
}
