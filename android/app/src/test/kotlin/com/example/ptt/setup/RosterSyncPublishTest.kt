package com.example.ptt.setup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RosterSyncPublishTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: AdminHarness
    private val f get() = h.f

    @Before
    fun setUp() = runBlocking {
        h = AdminHarness(tmp.newFolder())
        h.enrollAsBootstrapAdmin()
    }

    @After
    fun tearDown() = f.close()

    @Test
    fun anAdminPublishWaitsForARunningSyncInsteadOfInterleaving() = runBlocking {
        val gate = CountDownLatch(1)
        val getStarted = CountDownLatch(1)
        f.fake.on("GET", "/v1/roster") {
            getStarted.countDown()
            gate.await(5, TimeUnit.SECONDS)
            FakeServer.response(404, """{"error":"not_found"}""")
        }
        f.fake.reply("PUT", "/v1/roster", 204)
        val other = NewPhone()

        val sync = async(Dispatchers.IO) { f.sync.sync() }
        assertTrue(getStarted.await(5, TimeUnit.SECONDS))
        val publish = async(Dispatchers.IO) {
            f.sync.publishEdit { current ->
                RosterEdit.Next(com.example.ptt.crypto.RosterEditor.addDevice(current, other.entry, ISSUED_AT))
            }
        }
        delay(300)
        assertEquals("no PUT while the sync holds the lock", 0, h.puts())
        gate.countDown()
        sync.await()
        assertTrue(publish.await() is PublishResult.Published)
        assertEquals(1, h.puts())
        assertEquals(2L, f.rosters.current()!!.version)
    }

    @Test
    fun aPublishFromANonAdminOrWithoutARosterIsRefusedLocally() = runBlocking {
        f.rosters.clear()
        val before = f.fake.requests.size
        val result = f.sync.publishEdit { RosterEdit.Refuse("never") }
        assertEquals(PublishResult.NotAdmin, result)
        assertEquals(before, f.fake.requests.size)
    }

    @Test
    fun a401WhilePublishingWipesNothing() = runBlocking {
        f.fake.reply("PUT", "/v1/roster", 401, """{"error":"unauthorized"}""")
        val other = NewPhone()
        val result = f.sync.publishEdit { current ->
            RosterEdit.Next(com.example.ptt.crypto.RosterEditor.addDevice(current, other.entry, ISSUED_AT))
        }
        assertEquals(PublishResult.NotEnrolled, result)
        // The interceptor flagged re-enrollment; the existing routing wipes on the next resolve, not here.
        assertTrue(f.settings.needsReenrollment())
        assertEquals(1L, f.rosters.current()!!.version)
        assertTrue(f.tokens.deviceId() != null)
    }
}
