package com.example.ptt.talk

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.ptt.MainActivity
import com.example.ptt.net.DebugImpairment
import com.example.ptt.net.Impairment
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugReadoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() = DebugImpairment.set(Impairment())

    private fun show(live: TalkLive) {
        compose.runOnUiThread {
            compose.activity.setContent {
                TalkScreen(TalkState("p", "Mom", TalkPhase.ANSWERED), {}, {}, {}, live)
            }
        }
    }

    @Test
    fun theTalkScreenShowsTheImpairmentAndTheLastRtt() {
        DebugImpairment.set(Impairment(300, 5))
        show(TalkLive(eligible = true, active = true, rttMillis = 640))
        compose.onNodeWithText("Impairment: 300 ms each way, 5% frame loss").assertExists()
        compose.onNodeWithText("Last RTT: 640 ms").assertExists()
        compose.runOnUiThread { DebugImpairment.set(Impairment()) }
        compose.onNodeWithText("Impairment: off").assertExists()
    }

    @Test
    fun aTimedOutPingIsShownAsTimedOutNotAsItsTimeoutValue() {
        show(TalkLive(eligible = true, active = true, rttMillis = 800, rttTimedOut = true))
        compose.onNodeWithText("Last RTT: timed out").assertExists()
    }

    @Test
    fun withoutAnRttSampleItSaysSo() {
        show(TalkLive(eligible = true, active = true))
        compose.onNodeWithText("Last RTT: none yet").assertExists()
    }
}
