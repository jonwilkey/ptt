package com.example.ptt.setup

import com.example.ptt.crypto.DeviceKeysUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartOverTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var f: SetupFixture

    @Before
    fun setUp() {
        f = SetupFixture(tmp.newFolder())
    }

    @After
    fun tearDown() = f.close()

    @Test
    fun aPhoneWaitingForARosterCanStartOverFromScratch() = runBlocking {
        val admin = TestAdmin()
        f.serveEnroll(admin = false)
        assertEquals(EnrollResult.Success, f.controller.enroll(f.adminQr(admin), "Sam phone"))
        assertEquals(Route.Waiting, f.startup.resolve())

        assertEquals(Route.Enroll(explain = false), f.startup.startOver())

        assertFalse(f.settings.enrolled.first())
        assertNull(f.settings.profile())
        assertNull(f.tokens.token())
        assertNull(f.tokens.deviceId())
        assertNull(f.rosters.current())
        assertTrue(f.settings.trustAnchor().isEmpty())
        assertFalse(f.settings.needsReenrollment())
        try {
            f.keyStore.load()
            error("keys should be gone")
        } catch (_: DeviceKeysUnavailable) {
            // wiped
        }
        // Routing agrees: a plain, unexplained enrollment screen.
        assertEquals(Route.Enroll(explain = false), f.startup.resolve())
    }

    @Test
    fun startOverDoesNotWipeAPhoneWhoseRosterArrivedMeanwhile() = runBlocking {
        val admin = TestAdmin()
        f.serveEnroll(admin = false)
        f.controller.enroll(f.adminQr(admin), "Sam phone")
        assertEquals(Route.Waiting, f.startup.resolve())
        // The sync stores a roster after the Waiting screen was shown, before Confirm is tapped.
        f.serveRoster(f.signedRoster(admin.keys, admin.id, listOf(admin.device, f.selfEntry()), 1))
        f.sync.sync()
        assertEquals(Route.Home, f.startup.startOver())
        assertTrue(f.settings.enrolled.first())
        assertTrue(f.rosters.current() != null)
        assertTrue(f.tokens.token() != null)
    }

    @Test
    fun startOverWaitsForTheIdentityLockLikeEveryOtherTransition() = runBlocking {
        f.serveEnroll(admin = false)
        f.controller.enroll(f.adminQr(TestAdmin()), "Sam phone")
        f.wiper.lock.lock()
        val result = async(Dispatchers.IO) { f.startup.startOver() }
        delay(200)
        assertFalse("blocked while the lock is held", result.isCompleted)
        f.wiper.lock.unlock()
        assertEquals(Route.Enroll(explain = false), result.await())
    }
}
