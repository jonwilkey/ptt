package com.example.ptt.talk

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.example.ptt.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HoldToTalkTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val audio = FakeAudioSource("p")
    private val hold = HoldToTalk(audio)
    private val enabled = mutableStateOf(true)
    private val live = mutableStateOf(TalkLive(eligible = true, active = true))

    private fun show() {
        val state = TalkState("p", "Mom", TalkPhase.ANSWERED)
        compose.runOnUiThread {
            compose.activity.setContent {
                TalkScreen(
                    state,
                    {},
                    {},
                    {},
                    live.value.copy(talkEnabled = enabled.value),
                    { hold.press(HoldSource.BUTTON, "p", enabled.value) },
                    { hold.release(HoldSource.BUTTON) }
                )
            }
        }
    }

    @Test
    fun downReachesPressAndUpReachesRelease() {
        show()
        compose.onNodeWithText("Hold to talk").performTouchInput { down(center) }
        assertEquals(listOf("press"), audio.calls)
        compose.onNodeWithText("Hold to talk").performTouchInput { up() }
        assertEquals(listOf("press", "release"), audio.calls)
    }

    @Test
    fun cancelReleases() {
        show()
        compose.onNodeWithText("Hold to talk").performTouchInput { down(center) }
        compose.onNodeWithText("Hold to talk").performTouchInput { cancel() }
        assertEquals(listOf("press", "release"), audio.calls)
    }

    @Test
    fun aDisabledButtonDoesNotPress() {
        enabled.value = false
        show()
        compose.onNodeWithText("Hold to talk").performTouchInput {
            down(center)
            up()
        }
        assertEquals(emptyList<String>(), audio.calls)
    }

    @Test
    fun leavingTheCompositionWhileHeldReleases() {
        show()
        compose.onNodeWithText("Hold to talk").performTouchInput { down(center) }
        live.value = TalkLive()
        compose.waitForIdle()
        assertEquals(listOf("press", "release"), audio.calls)
    }

    @Test
    fun buttonAndVolumeShareOneHold() {
        hold.press(HoldSource.BUTTON, "p", true)
        hold.press(HoldSource.VOLUME_KEY, "p", true)
        assertEquals("one press for both holders", listOf("press"), audio.calls)
        hold.release(HoldSource.BUTTON)
        assertEquals("still held by the volume key", listOf("press"), audio.calls)
        hold.release(HoldSource.VOLUME_KEY)
        assertEquals(listOf("press", "release"), audio.calls)
    }

    @Test
    fun volumeAndButtonShareOneHoldInTheOtherOrder() {
        hold.press(HoldSource.VOLUME_KEY, "p", true)
        hold.press(HoldSource.BUTTON, "p", true)
        assertEquals(listOf("press"), audio.calls)
        hold.release(HoldSource.VOLUME_KEY)
        assertEquals(listOf("press"), audio.calls)
        hold.release(HoldSource.BUTTON)
        assertEquals(listOf("press", "release"), audio.calls)
    }

    @Test
    fun releaseAllEndsTheHoldWhicheverHolds() {
        hold.press(HoldSource.BUTTON, "p", true)
        hold.press(HoldSource.VOLUME_KEY, "p", true)
        hold.releaseAll()
        assertEquals(listOf("press", "release"), audio.calls)
        hold.releaseAll()
        hold.release(HoldSource.BUTTON)
        assertEquals("release is idempotent", listOf("press", "release"), audio.calls)
    }

    @Test
    fun aDisabledPressIsNotARequest() {
        hold.press(HoldSource.BUTTON, "p", false)
        hold.press(HoldSource.VOLUME_KEY, "p", false)
        assertEquals(emptyList<String>(), audio.calls)
        hold.release(HoldSource.BUTTON)
        hold.releaseAll()
        assertEquals("a refused press needs no release", emptyList<String>(), audio.calls)
    }
}
