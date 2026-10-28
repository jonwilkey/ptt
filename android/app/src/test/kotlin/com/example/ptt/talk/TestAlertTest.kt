package com.example.ptt.talk

import com.example.ptt.R
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.data.PageRole
import com.example.ptt.setup.ADMIN_USER
import com.example.ptt.setup.USER
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TestAlertTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: SenderHarness

    @Before
    fun setUp() {
        h = SenderHarness(tmp.newFolder())
    }

    @After
    fun tearDown() {
        h.close()
    }

    /** The page requests that reached the (fake) server: the outbox's loop runs with the virtual time. */
    private fun sentPages() = h.api.calls.count { it.startsWith("page:") }

    @Test
    fun theCountdownRunsThenAPageGoesToYourOwnUser() = runTest {
        val vm = with(h) { TestAlertViewModel(rig().sender, backgroundScope) }
        vm.start(EnvelopePayload.LEVEL_URGENT)
        runCurrent()
        assertEquals(TestAlertViewModel.COUNTDOWN_S, vm.state.value.secondsLeft)
        advanceTimeBy(5_500)
        assertEquals(5, vm.state.value.secondsLeft)
        assertEquals(0, sentPages())
        advanceTimeBy(5_000)
        assertNull(vm.state.value.secondsLeft)
        assertEquals(R.string.test_alert_sent, vm.state.value.message)
        val row = h.f.activePage.current()!!
        assertEquals(USER, row.peerUserId)
        assertEquals("urgent", row.level)
        assertEquals(PageRole.SENDER, row.role)
        assertEquals(1, sentPages())
    }

    @Test
    fun cancellingDuringTheCountdownSendsNothing() = runTest {
        val vm = with(h) { TestAlertViewModel(rig().sender, backgroundScope) }
        vm.start(EnvelopePayload.LEVEL_NORMAL)
        advanceTimeBy(4_000)
        vm.cancel()
        advanceTimeBy(60_000)
        assertEquals(TestAlertState(), vm.state.value)
        assertEquals(0, sentPages())
        assertNull(h.f.activePage.current())
        // A new test can start afterwards.
        vm.start(EnvelopePayload.LEVEL_NORMAL)
        advanceTimeBy(TestAlertViewModel.COUNTDOWN_S * 1_000L + 100)
        assertEquals(1, sentPages())
    }

    @Test
    fun aSecondStartWhileCountingDownChangesNothing() = runTest {
        val vm = with(h) { TestAlertViewModel(rig().sender, backgroundScope) }
        vm.start(EnvelopePayload.LEVEL_NORMAL)
        advanceTimeBy(3_000)
        vm.start(EnvelopePayload.LEVEL_URGENT)
        advanceTimeBy(20_000)
        assertEquals("normal", h.f.activePage.current()?.level)
        assertEquals(1, sentPages())
    }

    @Test
    fun aLivePageIsNotEndedForATest() = runTest {
        val rig = with(h) { rig() }
        rig.sender.send(ADMIN_USER, "normal")
        val vm = TestAlertViewModel(rig.sender, backgroundScope)
        vm.start(EnvelopePayload.LEVEL_NORMAL)
        advanceTimeBy(TestAlertViewModel.COUNTDOWN_S * 1_000L + 100)
        assertEquals(R.string.test_alert_busy, vm.state.value.message)
        assertEquals(1, sentPages())
    }
}
