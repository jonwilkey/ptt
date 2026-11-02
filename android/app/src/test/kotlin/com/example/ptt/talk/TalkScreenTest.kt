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
}
