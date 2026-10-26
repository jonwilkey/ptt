package com.example.ptt.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.ptt.crypto.TrustedAdmin
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

    @Test
    fun profileTrustAnchorAndRegistrationRoundTripAndAreCleared() = runBlocking {
        val s = repo()
        assertNull(s.profile())
        assertTrue(s.trustAnchor().isEmpty())
        assertNull(s.lastRegistration())

        s.saveProfile(DeviceProfile("u", "Sam", true, "Sam phone"))
        val id = "c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57"
        s.setTrustAnchor(
            listOf(TrustedAdmin(id, ByteArray(32) { 5 }), TrustedAdmin(id.reversed(), ByteArray(32) { 6 }))
        )
        s.setLastRegistration(RegistrationValues("0.2.0", "Sam phone", "abc"))
        assertEquals(DeviceProfile("u", "Sam", true, "Sam phone"), s.profile())
        assertEquals(listOf(id, id.reversed()), s.trustAnchor().map { it.id })
        assertTrue(s.trustAnchor()[1].ed25519.contentEquals(ByteArray(32) { 6 }))
        assertEquals(RegistrationValues("0.2.0", "Sam phone", "abc"), s.lastRegistration())

        s.clearTrustAnchor()
        assertTrue(s.trustAnchor().isEmpty())
        s.clearEnrollment()
        assertNull(s.profile())
        assertNull(s.lastRegistration())
    }
}
