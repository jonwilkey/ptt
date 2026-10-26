package com.example.ptt.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = SettingsRepository(
        PreferenceDataStoreFactory.create { tmp.newFile("s${System.nanoTime()}.preferences_pb") }
    )

    @Test
    fun startsNotEnrolled() = runBlocking {
        val s = repo()
        assertNull(s.serverUrl())
        assertFalse(s.enrolled.first())
        assertFalse(s.needsReenrollment())
        assertTrue(s.normalAlertVibrate.first())
        assertTrue(s.urgentAlertMaxVolume.first())
    }

    @Test
    fun enrollmentRoundTripAndReenrollFlag() = runBlocking {
        val s = repo()
        s.setEnrolled("https://ptt.example.com")
        assertEquals("https://ptt.example.com", s.serverUrl())
        assertTrue(s.enrolled.first())

        s.markNeedsReenrollment()
        assertTrue(s.needsReenrollment())
        // Marking does not forget the server or the enrolled state: re-enrollment decides that.
        assertEquals("https://ptt.example.com", s.serverUrl())

        s.setEnrolled("https://ptt.example.com")
        assertFalse(s.needsReenrollment())

        s.clearEnrollment()
        assertNull(s.serverUrl())
        assertFalse(s.enrolled.first())
    }

    @Test
    fun alertPlaceholders() = runBlocking {
        val s = repo()
        s.setNormalAlertVibrate(false)
        s.setUrgentAlertMaxVolume(false)
        assertFalse(s.normalAlertVibrate.first())
        assertFalse(s.urgentAlertMaxVolume.first())
    }
}
