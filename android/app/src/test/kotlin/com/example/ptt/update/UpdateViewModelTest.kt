package com.example.ptt.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.ApiResult
import com.example.ptt.net.AppLatestResponse
import com.example.ptt.net.PttApi
import com.example.ptt.outbox.FakePttApi
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var latest: ApiResult<AppLatestResponse> = ApiResult.Retryable(status = 503)
    private val api = object : PttApi by FakePttApi() {
        override suspend fun getAppLatest() = latest
    }
    private val packages = object : PackageFacts {
        override fun installed() = ApkFacts("com.example.ptt", 4000, listOf("c1"))

        override fun archive(file: File): ApkFacts? = null
    }

    @After
    fun tearDown() {
        InstallPrompt.clearDirect()
    }

    private fun viewModel(): UpdateViewModel {
        val settings = SettingsRepository(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") })
        val checker = UpdateChecker(
            api,
            settings,
            packages,
            ApkInstaller { _, _ -> true },
            { false },
            Clock.systemUTC(),
            File(tmp.root, "update"),
            Dispatchers.Unconfined
        )
        return UpdateViewModel(MinSupported(settings, 4000), checker, CoroutineScope(Job() + Dispatchers.Unconfined))
    }

    @Test(timeout = 60_000)
    fun aPressThatDoesNotEndInstallingLeavesNoDirectFlagBehind() {
        val vm = viewModel()
        vm.updateNow()
        assertTrue(vm.status.value is UpdateOutcome.Failed)
        assertFalse(InstallPrompt.consumeDirect())
    }

    @Test(timeout = 60_000)
    fun theDirectFlagIsConsumedOnce() {
        InstallPrompt.expectDirect()
        assertTrue(InstallPrompt.consumeDirect())
        assertEquals(false, InstallPrompt.consumeDirect())
    }
}
