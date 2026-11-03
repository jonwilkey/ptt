package com.example.ptt.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.ptt.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MinSupportedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = lazy {
        SettingsRepository(PreferenceDataStoreFactory.create { tmp.newFile("s${System.nanoTime()}.preferences_pb") })
    }

    @Test(timeout = 60_000)
    fun nothingIsRequiredByDefault() = runBlocking {
        assertFalse(MinSupported(settings.value, 1).isRequired())
    }

    @Test(timeout = 60_000)
    fun anOlderInstalledCodeIsBlockedAndAnEqualOrNewerOneIsNot() = runBlocking {
        settings.value.setMinSupportedCode(4000)
        assertTrue(MinSupported(settings.value, 3999).isRequired())
        assertFalse(MinSupported(settings.value, 4000).isRequired())
        assertFalse(MinSupported(settings.value, 5000).isRequired())
    }
}
