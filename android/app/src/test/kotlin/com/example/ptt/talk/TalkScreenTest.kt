package com.example.ptt.talk

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.ptt.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TalkScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun eachProgressHasItsLabelAndLiveOnesOfferEndWhileFinalOnesOfferClose() {
        val state = mutableStateOf(TalkState("p", "Mom", TalkPhase.OUTGOING, sender = true))
        compose.runOnUiThread {
            compose.activity.setContent { TalkScreen(state.value, {}, {}, {}) }
        }
        val labels = mapOf(
            SendProgress.SENDING to "Sending",
            SendProgress.NOT_DELIVERED to "Not delivered yet",
            SendProgress.DELIVERED to "Delivered",
            SendProgress.ACKNOWLEDGED to "Acknowledged",
            SendProgress.ANSWERED to "Answered",
            SendProgress.NO_ANSWER to "No answer",
            SendProgress.FAILED to "Failed"
        )
        for ((progress, label) in labels) {
            state.value = state.value.copy(progress = progress)
            compose.waitForIdle()
            compose.onNodeWithText(label).assertExists()
            compose.onNodeWithText(if (progress.terminal) "Close" else "End").assertExists()
            compose.onNodeWithText("Page to Mom").assertExists()
        }
        state.value = state.value.copy(progress = SendProgress.ANSWERED)
        compose.waitForIdle()
        compose.onNodeWithText("There is no live talk for a test alert.").assertExists()
    }

    @Test
    fun theConversationSaysClipModeAndThatAnAbsentPeerIsAway() {
        val live = mutableStateOf(
            TalkLive(
                eligible = true,
                active = true,
                connection = TalkConnection.LIVE,
                quality = LinkQuality.POOR,
                clipMode = true,
                waitingForPeer = true,
                talkEnabled = true
            )
        )
        compose.runOnUiThread {
            compose.activity.setContent {
                TalkScreen(TalkState("p", "Mom", TalkPhase.ANSWERED), {}, {}, {}, live.value)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Clip mode · poor connection").assertExists()
        compose.onNodeWithText("Mom is away").assertExists()
        live.value = live.value.copy(clipMode = false, quality = LinkQuality.GOOD, talkEnabled = false)
        compose.waitForIdle()
        compose.onNodeWithText("Live · good connection").assertExists()
        compose.onNodeWithText("Clip mode", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Waiting for Mom").assertExists()
        live.value = live.value.copy(clipMode = true, connection = TalkConnection.CONNECTING)
        compose.waitForIdle()
        compose.onNodeWithText("Clip mode", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Connecting…").assertExists()
    }
}
