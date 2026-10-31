package com.example.ptt.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTalkAuthTest {
    @Test(timeout = 60_000)
    fun rejectionOfTheCurrentTokenFlagsReenrollment() {
        val state = FakeEnrollmentState()
        val auth = DeviceTalkAuth(InMemoryDeviceTokenStore("current", "d"), state)
        auth.rejected("current")
        assertTrue(state.needsReenroll)
    }

    @Test(timeout = 60_000)
    fun aLateRejectionOfAnOldTokenDoesNot() {
        val state = FakeEnrollmentState()
        val auth = DeviceTalkAuth(InMemoryDeviceTokenStore("fresh", "d"), state)
        auth.rejected("old")
        assertFalse(state.needsReenroll)
    }

    @Test(timeout = 60_000)
    fun anUnreadableTokenIsNullAndNeverFlagged() {
        val state = FakeEnrollmentState()
        val auth = DeviceTalkAuth(InMemoryDeviceTokenStore("t", "d", DeviceTokenUnavailable("x")), state)
        assertNull(auth.token())
        auth.rejected("t")
        assertFalse(state.needsReenroll)
        assertEquals("t", DeviceTalkAuth(InMemoryDeviceTokenStore("t", "d"), state).token())
    }
}
