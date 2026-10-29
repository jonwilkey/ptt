package com.example.ptt.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetupViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: AdminHarness
    private val f get() = h.f

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        h = AdminHarness(tmp.newFolder())
        h.enrollAsBootstrapAdmin()
    }

    @After
    fun tearDown() {
        f.close()
        Dispatchers.resetMain()
    }

    /** A probe that works while the checklist is built and then throws, like a platform call failing later. */
    private class ExplodingProbe : SystemProbe {
        var explode = false
        override val manufacturer = "google"

        private fun read(): Boolean? = if (explode) throw ClassCastException("test") else true
        override fun notificationPermission() = read()
        override fun notificationsEnabled() = read()
        override fun lockScreenNotifications() = read()
        override fun microphone() = read()
        override fun fullScreenIntent() = read()
        override fun dndAccess() = read()
        override fun ignoringBatteryOptimizations() = read()
        override fun canInstallPackages() = read()
        override fun urgentChannelBypassesDnd() = read()
    }

    @Test
    fun aFailingChecklistStepStillLetsTheFcmTokenBeUploaded() {
        f.serveContacts()
        f.fake.reply("GET", "/v1/roster", 404)
        f.fake.reply("PUT", "/v1/devices/me", 204)
        f.fcmToken = "fcm-token-1"
        val probe = ExplodingProbe()
        val checklist = SetupChecklist(probe)
        probe.explode = true
        // As in production: the registration reads the same (failing) checklist the view model refreshes.
        val registration = DeviceRegistration(f.api, f.settings, f.fcm, f.appVersion, checklist)
        val vm = SetupViewModel(
            f.startup,
            f.controller,
            f.sync,
            registration,
            checklist,
            h.revoker,
            f.settings,
            f.rosters,
            f.tokens
        )

        vm.onStart()

        waitFor("the registration PUT") { f.fake.count("PUT", "/v1/devices/me") >= 1 }
        assertTrue(f.fake.bodies("PUT", "/v1/devices/me").any { it.contains("fcm_token") })
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }
}
