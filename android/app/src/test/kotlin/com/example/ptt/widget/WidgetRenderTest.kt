package com.example.ptt.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import com.example.ptt.MainActivity
import com.example.ptt.R
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.setup.ADMIN_USER
import com.example.ptt.talk.FamilyPerson
import com.example.ptt.talk.LEE_USER
import com.example.ptt.talk.SendIntents
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Renders the widget content composable with Glance's unit-test harness: no launcher, activity or Hilt app. */
@RunWith(RobolectricTestRunner::class)
class WidgetRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pat = FamilyPerson(ADMIN_USER, "Pat")
    private val lee = FamilyPerson(LEE_USER, "Lee")

    private fun family(warning: Boolean = false, people: List<WidgetPerson> = rows()) =
        WidgetState.Family(people, warning)

    private fun rows() = listOf(WidgetPerson(pat, WidgetPresence.OK), WidgetPerson(lee, WidgetPresence.OFFLINE))

    private fun render(
        state: WidgetState,
        block: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit
    ) = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { WidgetContent(state) }
        block()
    }

    @Test(timeout = 60_000)
    fun everyPersonHasANamePresenceDotAndPageAndUrgentButtons() = render(family()) {
        onNode(hasText("Pat")).assertExists()
        onNode(hasText("Lee")).assertExists()
        onNode(hasContentDescription(context.getString(R.string.widget_presence_ok, "Pat"))).assertExists()
        onNode(hasContentDescription(context.getString(R.string.widget_presence_offline, "Lee"))).assertExists()
        for (p in listOf(pat, lee)) {
            onNode(hasContentDescription(context.getString(R.string.widget_page_cd, p.name)))
                .assertHasStartActivityClickAction(
                    SendIntents.intent(context, p.userId, EnvelopePayload.LEVEL_NORMAL, p.name)
                )
            onNode(hasContentDescription(context.getString(R.string.widget_urgent_cd, p.name)))
                .assertHasStartActivityClickAction(
                    SendIntents.intent(context, p.userId, EnvelopePayload.LEVEL_URGENT, p.name)
                )
        }
    }

    @Test(timeout = 60_000)
    fun thisPhonesOwnUserIsNotARow() = render(family()) {
        // The state mapping drops self (WidgetTest); the content draws exactly the people it is given.
        onNode(hasText("Sam")).assertDoesNotExist()
    }

    @Test(timeout = 60_000)
    fun theBadgeIsShownOnlyWhenTheChecklistRegressedAndOpensTheApp() {
        val badge = context.getString(R.string.widget_checklist_warning)
        render(family(warning = true)) {
            onNode(hasText(badge)).assertExists()
            onNode(hasText(badge)).assertHasStartActivityClickAction(Intent(context, MainActivity::class.java))
        }
        render(family(warning = false)) { onNode(hasText(badge)).assertDoesNotExist() }
    }

    @Test(timeout = 60_000)
    fun notEnrolledAsksToOpenTheAppAndTapsOpenIt() = render(WidgetState.NeedsSetup) {
        val text = context.getString(R.string.widget_needs_setup)
        onNode(hasText(text)).assertExists()
        onNode(hasText(text)).assertHasStartActivityClickAction(Intent(context, MainActivity::class.java))
        onNode(hasStartActivityClickAction(Intent(context, MainActivity::class.java))).assertExists()
        onNode(hasText("Pat")).assertDoesNotExist()
    }

    @Test(timeout = 60_000)
    fun nobodyToPageShowsItsOwnMessage() = render(family(people = emptyList())) {
        onNode(hasText(context.getString(R.string.widget_nobody))).assertExists()
        onNode(hasContentDescription(context.getString(R.string.widget_page_cd, "Pat"))).assertDoesNotExist()
    }
}
