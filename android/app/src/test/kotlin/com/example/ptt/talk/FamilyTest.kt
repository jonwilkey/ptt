package com.example.ptt.talk

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ptt.MainActivity
import com.example.ptt.R
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.data.OutboxKind
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.setup.ADMIN_USER
import com.example.ptt.setup.ISSUED_AT
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FamilyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var h: SenderHarness
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pat = FamilyPerson(ADMIN_USER, "Pat")
    private val lee = FamilyPerson(LEE_USER, "Lee")

    @Before
    fun setUp() {
        h = SenderHarness(tmp.newFolder())
    }

    @After
    fun tearDown() {
        appScope.cancel()
        h.close()
    }

    private fun viewModel(sender: PageSender) = FamilyViewModel(h.f.rosters, h.f.tokens, sender, appScope)

    private suspend fun pageRows() = h.f.db.outboxDao().all().filter { it.kind == OutboxKind.PAGE }

    @Test
    fun theListHasEachOtherPersonOnceAndNotYourself() = runTest {
        val vm = with(h) { viewModel(rig().sender) }
        // Pat has two phones in the roster; this phone's own user (Sam) is not offered.
        assertEquals(setOf(pat, lee), vm.people.first()!!.toSet())
        assertEquals(2, vm.people.first()!!.size)
    }

    @Test
    fun aTapSendsALevelPageAndOpensTheOutgoingScreen() = runTest {
        val vm = with(h) { viewModel(rig().sender) }
        val events = CopyOnWriteArrayList<FamilyEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        vm.page(pat, EnvelopePayload.LEVEL_URGENT)
        awaitEvents(events, 1)
        val open = events.single() as FamilyEvent.Open
        assertEquals("Pat", open.name)
        val request = Json.decodeFromString(CreatePageRequest.serializer(), pageRows().single().body)
        assertEquals(open.pageId, request.id)
        assertEquals(ADMIN_USER, request.to)
        assertEquals("urgent", request.level)
    }

    @Test
    fun aLivePageAsksBeforeItIsEnded() = runTest {
        val vm = with(h) { viewModel(rig().sender) }
        val events = CopyOnWriteArrayList<FamilyEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        vm.page(pat, "normal")
        awaitEvents(events, 1)
        vm.page(lee, "urgent")
        awaitConfirm(vm)
        assertEquals(FamilyConfirm(lee, "urgent", "Pat"), vm.confirm.value)
        assertEquals(1, pageRows().size)
        vm.confirmEnd()
        awaitEvents(events, 2)
        assertNull(vm.confirm.value)
        assertEquals("Lee", (events[1] as FamilyEvent.Open).name)
        assertEquals(LEE_USER, h.f.activePage.current()?.peerUserId)
    }

    @Test
    fun aPersonWithNoPhonesIsSaidSo() = runTest {
        val vm = with(h) { viewModel(rig().sender) }
        val events = CopyOnWriteArrayList<FamilyEvent>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.events.toList(events) }
        vm.page(FamilyPerson("99999999-9999-4999-8999-999999999999", "Ghost"), "normal")
        awaitEvents(events, 1)
        assertEquals(FamilyEvent.Message(R.string.family_no_phones, "Ghost"), events.single())
        assertTrue(pageRows().isEmpty())
    }

    @Test
    fun theButtonsSendTheRightLevelAndOpenTheOutgoingIntent() {
        val rig = h.rig(appScope) { ISSUED_AT }
        val vm = viewModel(rig.sender)
        compose.runOnUiThread { compose.activity.setContent { FamilyContent(vm) { } } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Lee")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Urgent page to Lee").performClick()
        compose.waitUntil(5_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(TalkActivity::class.java.name, started.component?.className)
        assertEquals(TalkIntents.MODE_OUTGOING, started.getStringExtra(TalkIntents.EXTRA_MODE))
        assertEquals("Lee", started.getStringExtra(TalkIntents.EXTRA_CALLER_NAME))
        val pageId = started.getStringExtra(TalkIntents.EXTRA_PAGE_ID)
        val row = runBlocking { h.f.activePage.current() }!!
        assertEquals(pageId, row.pageId)
        assertEquals("urgent", row.level)
        assertEquals(LEE_USER, row.peerUserId)
    }

    @Test
    fun theConfirmationDialogEndsOrKeepsTheCurrentPage() {
        var confirmed = 0
        var dismissed = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                FamilyScreen(
                    listOf(pat),
                    FamilyConfirm(pat, "normal", "Mom"),
                    null,
                    { _, _ -> },
                    { confirmed++ },
                    { dismissed++ },
                    { }
                )
            }
        }
        compose.onNodeWithText("End the current page with Mom?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("End it and page").performClick()
        assertEquals(1, dismissed)
        assertEquals(1, confirmed)
    }

    @Test
    fun anEmptyFamilySaysSo() {
        compose.runOnUiThread {
            compose.activity.setContent { FamilyScreen(emptyList(), null, null, { _, _ -> }, {}, {}, {}) }
        }
        compose.onNodeWithText("No one else is in the family yet.", substring = true).assertExists()
    }

    /** The send runs on a real dispatcher: poll briefly instead of advancing virtual time. */
    private fun awaitEvents(events: List<FamilyEvent>, count: Int) = poll { events.size >= count }

    private fun awaitConfirm(vm: FamilyViewModel) = poll { vm.confirm.value != null }

    private fun poll(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aMissingRosterIsNotAnEmptyFamily() = runTest {
        val vm = with(h) { viewModel(rig().sender) }
        h.f.rosters.clear()
        assertNull(vm.people.first())
    }
}
