package com.example.ptt.setup

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
class DeviceRegistrationTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: AdminHarness
    private val f get() = h.f
    private val put = "/v1/devices/me"

    @Before
    fun setUp() = runBlocking {
        h = AdminHarness(tmp.newFolder())
        h.enrollAsBootstrapAdmin()
        f.fake.reply("PUT", put, 204)
    }

    @After
    fun tearDown() = f.close()

    @Test
    fun anUnreadableChecklistDoesNotStopTheTokenAndIsKeptForLater() = runBlocking {
        var broken = false
        f.checklistSource = ChecklistSource {
            if (broken) throw ClassCastException("test") else mapOf("microphone" to true)
        }
        f.fcmToken = "fcm-one"
        assertTrue(f.registration.sync())
        assertEquals(1, f.fake.count("PUT", put))

        broken = true
        f.fcmToken = "fcm-two"
        assertTrue(f.registration.sync())
        // Only the token: no checklist is sent (or cleared) while it cannot be read.
        assertEquals("""{"fcm_token":"fcm-two"}""", f.fake.bodies("PUT", put).last())

        // Readable again and unchanged since the last report: nothing to send.
        broken = false
        assertTrue(f.registration.sync())
        assertEquals(2, f.fake.count("PUT", put))
    }
}
