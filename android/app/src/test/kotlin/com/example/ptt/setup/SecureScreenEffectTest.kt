package com.example.ptt.setup

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.example.ptt.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureScreenEffectTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theQrScreenAsksForASecureWindowWhileShownAndReleasesItAfter() {
        val calls = mutableListOf<Boolean>()
        val show = mutableStateOf(true)
        composeRule.runOnUiThread {
            composeRule.activity.setContent { if (show.value) SecureScreenEffect { calls += it } else Text("gone") }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(true), calls)
        show.value = false
        composeRule.waitForIdle()
        assertEquals(listOf(true, false), calls)
    }
}
