package com.example.ptt.talk

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TalkActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theIntentContractCarriesPageIdModeAndCaller() {
        val intent = TalkIntents.intent(app, "page-1", TalkIntents.MODE_INCOMING, "Mom")
        assertEquals(TalkActivity::class.java.name, intent.component?.className)
        assertEquals("page-1", intent.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
        assertEquals(TalkIntents.MODE_INCOMING, intent.getStringExtra(TalkIntents.EXTRA_MODE))
        assertEquals("Mom", intent.getStringExtra(TalkIntents.EXTRA_CALLER_NAME))
        val unnamed = TalkIntents.intent(app, "p", TalkIntents.MODE_ANSWER, null)
        assertNull(unnamed.getStringExtra(TalkIntents.EXTRA_CALLER_NAME))
    }

    @Test
    fun theStubShowsTheCallerAndThePageId() {
        ActivityScenario.launch<TalkActivity>(TalkIntents.intent(app, "page-2", TalkIntents.MODE_ANSWER, "Mom")).use {
            compose.onNodeWithText("Page from Mom").assertExists()
            compose.onNodeWithText("Page id: page-2").assertExists()
        }
    }

    @Test
    fun theStubSurvivesAMissingCallerName() {
        ActivityScenario.launch<TalkActivity>(TalkIntents.intent(app, "page-3", TalkIntents.MODE_OUTGOING, null)).use {
            compose.onNodeWithText("Page").assertExists()
        }
    }
}
