package com.example.ptt.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ptt.R
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.net.ContactDto
import com.example.ptt.setup.ADMIN_USER
import com.example.ptt.setup.AndroidSystemProbe
import com.example.ptt.setup.SetupChecklist
import com.example.ptt.talk.FamilyPerson
import com.example.ptt.talk.LEE_USER
import com.example.ptt.talk.SendIntents
import com.example.ptt.talk.SenderHarness
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/** The widget's state mapping, intents, updater and provider info. No activity or Hilt app is started. */
@RunWith(RobolectricTestRunner::class)
class WidgetTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: SenderHarness
    private lateinit var scope: CoroutineScope
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        h = SenderHarness(tmp.newFolder())
        scope = CoroutineScope(Dispatchers.Default + Job())
    }

    @After
    fun tearDown() {
        scope.cancel()
        h.close()
    }

    private fun states() = WidgetStates(h.f.rosters, h.f.contacts, h.f.settings, h.f.tokens)

    private fun contact(user: String, presence: String) = ContactDto(user, "x", false, presence)

    private suspend fun family() = states().current() as WidgetState.Family

    @Test(timeout = 60_000)
    fun rowsAreEveryoneInTheRosterOnceAndNotThisPhonesUser() = runBlocking {
        val people = family().people.map { it.person }
        // Pat has two phones; Sam, this phone's user, is left out.
        assertEquals(setOf(FamilyPerson(ADMIN_USER, "Pat"), FamilyPerson(LEE_USER, "Lee")), people.toSet())
        assertEquals(2, people.size)
    }

    @Test(timeout = 60_000)
    fun presenceIsMappedAndAnUnknownOrMissingOneIsOffline() = runBlocking {
        assertTrue(family().people.all { it.presence == WidgetPresence.OFFLINE })
        h.f.contacts.replaceAll(listOf(contact(ADMIN_USER, "warning"), contact(LEE_USER, "ok")))
        val byUser = family().people.associate { it.person.userId to it.presence }
        assertEquals(WidgetPresence.WARNING, byUser[ADMIN_USER])
        assertEquals(WidgetPresence.OK, byUser[LEE_USER])
        h.f.contacts.replaceAll(listOf(contact(ADMIN_USER, "bogus")))
        assertEquals(WidgetPresence.OFFLINE, family().people.first { it.person.userId == ADMIN_USER }.presence)
        assertEquals(WidgetPresence.OFFLINE, WidgetPresence.of(null))
    }

    @Test(timeout = 60_000)
    fun theBadgeShowsExactlyWhenTheChecklistRegressed() = runBlocking {
        assertFalse(family().checklistWarning)
        h.f.settings.setChecklistRegressed(true)
        assertTrue(family().checklistWarning)
        h.f.settings.setChecklistRegressed(false)
        assertFalse(family().checklistWarning)
    }

    @Test(timeout = 60_000)
    fun notEnrolledOrWithoutAVerifiedRosterTheWidgetAsksToOpenTheApp() = runBlocking {
        h.f.settings.clearEnrollment()
        assertEquals(WidgetState.NeedsSetup, states().current())
        h.f.settings.setEnrolled(h.f.url)
        h.f.rosters.save(2, "{}", "not a roster")
        assertEquals(WidgetState.NeedsSetup, states().current())
    }

    @Test(timeout = 60_000)
    fun everyButtonHasItsOwnIntent() {
        val people = listOf(FamilyPerson(ADMIN_USER, "Pat"), FamilyPerson(LEE_USER, "Lee"))
        val intents = people.flatMap { sendIntents(context, it).toList() }
        assertEquals(4, intents.size)
        for (a in intents.indices) {
            for (b in a + 1 until intents.size) assertFalse(intents[a].filterEquals(intents[b]))
        }
        val (page, urgent) = sendIntents(context, people[0])
        assertEquals(EnvelopePayload.LEVEL_NORMAL, page.getStringExtra(SendIntents.EXTRA_LEVEL))
        assertEquals(EnvelopePayload.LEVEL_URGENT, urgent.getStringExtra(SendIntents.EXTRA_LEVEL))
        assertEquals(ADMIN_USER, page.getStringExtra(SendIntents.EXTRA_USER_ID))
        assertEquals("Pat", page.getStringExtra(SendIntents.EXTRA_NAME))
    }

    @Test(timeout = 60_000)
    fun theUpdaterRecordsTheChecklistAndRedrawsOnChangesOnly() = runBlocking {
        val draws = AtomicInteger()
        val checklist = SetupChecklist(AndroidSystemProbe(context))
        WidgetUpdater(states(), checklist, h.f.settings, scope) { draws.incrementAndGet() }.start()
        // Robolectric grants nothing, so the checklist has red rows: recorded for the widget badge.
        withTimeout(10_000) { while (!h.f.settings.checklistRegressed.first()) delay(50) }
        withTimeout(10_000) { while (draws.get() < 1) delay(50) }
        delay(WidgetUpdater.DEBOUNCE_MS * 2)
        val settled = draws.get()
        // Same contacts again (a sync that changed nothing the widget shows) does not redraw.
        h.f.contacts.replaceAll(emptyList())
        delay(WidgetUpdater.DEBOUNCE_MS * 2)
        assertEquals(settled, draws.get())
        h.f.contacts.replaceAll(listOf(contact(LEE_USER, "ok")))
        withTimeout(10_000) { while (draws.get() == settled) delay(50) }
    }

    @Test(timeout = 60_000)
    fun theBaseProviderInfoForOlderAndroidIsResizableWithMinimumSizes() {
        // Robolectric here only has API 33+ (which resolves the xml-v31 copy), so read the base file itself.
        val text = java.io.File("src/main/res/xml/family_widget_info.xml").readText()
        fun attr(name: String) = Regex("android:$name=\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        assertEquals("250dp", attr("minWidth"))
        assertEquals("110dp", attr("minHeight"))
        assertEquals("250dp", attr("minResizeWidth"))
        assertEquals("horizontal|vertical", attr("resizeMode"))
        assertEquals("0", attr("updatePeriodMillis"))
        assertEquals("home_screen", attr("widgetCategory"))
        assertEquals(null, attr("targetCellWidth"))
    }

    @Test(timeout = 60_000)
    fun theProviderIsResizableAndDefaultsToFourByTwo() {
        val parser = context.resources.getXml(R.xml.family_widget_info)
        while (parser.eventType != XmlPullParser.START_TAG) parser.next()
        assertEquals("appwidget-provider", parser.name)
        val ns = "http://schemas.android.com/apk/res/android"
        fun int(name: String) = parser.getAttributeIntValue(ns, name, Int.MIN_VALUE)
        // Flag and enum attributes come back as their numbers: horizontal(1)|vertical(2), home_screen(1).
        assertEquals(1 or 2, int("resizeMode"))
        assertEquals(1, int("widgetCategory"))
        assertEquals(0, int("updatePeriodMillis"))
        assertEquals(4, int("targetCellWidth"))
        assertEquals(2, int("targetCellHeight"))
        assertNotEquals(null, parser.getAttributeValue(ns, "minResizeWidth"))
        assertNotEquals(null, parser.getAttributeValue(ns, "description"))
        assertNotEquals(null, parser.getAttributeValue(ns, "previewLayout"))
    }
}
