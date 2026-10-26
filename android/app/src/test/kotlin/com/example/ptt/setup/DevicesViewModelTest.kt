package com.example.ptt.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DevicesViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: AdminHarness
    private val f get() = h.f
    private val other = NewPhone()
    private val revokePath get() = "/v1/devices/${other.id}/revoke"

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        h = AdminHarness(tmp.newFolder())
        h.enrollAsBootstrapAdmin()
        h.storeRoster(2, listOf(h.self, other.entry))
    }

    @After
    fun tearDown() {
        f.close()
        Dispatchers.resetMain()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aServerRefusedRevokeMapsToItsOwnMessage() = runBlocking {
        val vm = DevicesViewModel(h.revoker, f.rosters, f.settings, f.tokens)
        assertEquals(DevicesMessage.SERVER_REFUSED, vm.messageFor(RevokeResult.ServerRefusedRevoke))
        assertEquals(DevicesMessage.REMOVED, vm.messageFor(RevokeResult.Done))
    }

    @Test
    fun theRetryLoopFinishesAPendingRevokeAndStopsWhenCancelled() = runBlocking {
        f.fake.reply("PUT", "/v1/roster", 204)
        f.fake.reply("POST", revokePath, 503)
        val vm = DevicesViewModel(h.revoker, f.rosters, f.settings, f.tokens)
        vm.remove(other.id)
        waitFor("removal") { f.fake.count("POST", revokePath) >= 1 }
        waitFor("pending stored") { runBlocking { f.settings.pendingRevocations() }.isNotEmpty() }

        val loop = launch(Dispatchers.Default) { vm.retryLoop(firstMs = 10, maxMs = 20, idleMs = 10) }
        waitFor("a retry") { f.fake.count("POST", revokePath) >= 3 }
        f.fake.reply("POST", revokePath, 204)
        waitFor("revoked") { runBlocking { f.settings.pendingRevocations() }.isEmpty() }

        // It keeps running (suspended) while visible, and stops completely when the screen stops.
        assertTrue(loop.isActive)
        loop.cancel()
        loop.join()
        val calls = f.fake.requests.size
        delay(100)
        assertEquals(calls, f.fake.requests.size)
    }
}
