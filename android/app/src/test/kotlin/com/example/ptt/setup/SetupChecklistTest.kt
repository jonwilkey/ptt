package com.example.ptt.setup

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SetupChecklistTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    private fun probe() = AndroidSystemProbe(app)

    private fun status(item: ChecklistItem, probe: SystemProbe = probe()) =
        SetupChecklist(probe).evaluate().rows.first { it.item == item }.status

    private fun allGranted() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
        shadowOf(notifications).setNotificationsEnabled(true)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        Settings.Secure.putInt(app.contentResolver, "lock_screen_show_notifications", 1)
    }

    // ---- each row, from shadowed system state ----

    @Test
    fun notificationsNeedBothThePermissionAndAppNotificationsOn() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        assertEquals(RowStatus.PASS, status(ChecklistItem.NOTIFICATIONS))
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.NOTIFICATIONS))
        shadowOf(notifications).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.NOTIFICATIONS))
    }

    @Test
    fun microphoneFollowsTheRuntimePermission() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.MICROPHONE))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(RowStatus.PASS, status(ChecklistItem.MICROPHONE))
    }

    @Test
    fun dndAccessFollowsThePolicyAccessGrant() {
        shadowOf(notifications).setNotificationPolicyAccessGranted(false)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.DND_ACCESS))
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        assertEquals(RowStatus.PASS, status(ChecklistItem.DND_ACCESS))
    }

    @Test
    fun batteryFollowsTheIgnoreOptimizationsExemption() {
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(app.packageName, false)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.BATTERY))
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        assertEquals(RowStatus.PASS, status(ChecklistItem.BATTERY))
    }

    @Test
    fun installUnknownAppsFollowsCanRequestPackageInstalls() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.INSTALL_PACKAGES))
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertEquals(RowStatus.PASS, status(ChecklistItem.INSTALL_PACKAGES))
    }

    @Test
    fun lockScreenIsGreenOnlyWhenEveryReadableSignalAgrees() {
        allGranted()
        assertEquals(RowStatus.PASS, status(ChecklistItem.LOCK_SCREEN))
        // The global switch off.
        Settings.Secure.putInt(app.contentResolver, "lock_screen_show_notifications", 0)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.LOCK_SCREEN))
        // A channel that hides content on the lock screen.
        Settings.Secure.putInt(app.contentResolver, "lock_screen_show_notifications", 1)
        val channel = NotificationChannel("alerts", "Alerts", NotificationManager.IMPORTANCE_HIGH)
        channel.lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        notifications.createNotificationChannel(channel)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.LOCK_SCREEN))
    }

    @Test
    fun lockScreenIsNeverGreenWhenTheGlobalSettingCannotBeRead() {
        allGranted()
        // Not green: Android does not expose the per-app setting, and the global one is unreadable here.
        Settings.Secure.putString(app.contentResolver, "lock_screen_show_notifications", null)
        assertEquals(RowStatus.UNKNOWN, status(ChecklistItem.LOCK_SCREEN))
    }

    // ---- states a probe cannot read are never green; informational rows never raise the badge ----

    @Suppress("LongParameterList")
    private class FakeProbe(
        override val manufacturer: String = "google",
        var notificationPermission: Boolean? = true,
        var notificationsEnabled: Boolean? = true,
        var lockScreen: Boolean? = true,
        var microphone: Boolean? = true,
        var fullScreen: Boolean? = true,
        var dnd: Boolean? = true,
        var urgentChannel: Boolean? = true,
        var battery: Boolean? = true,
        var install: Boolean? = true
    ) : SystemProbe {
        override fun notificationPermission() = notificationPermission
        override fun notificationsEnabled() = notificationsEnabled
        override fun lockScreenNotifications() = lockScreen
        override fun microphone() = microphone
        override fun fullScreenIntent() = fullScreen
        override fun dndAccess() = dnd
        override fun ignoringBatteryOptimizations() = battery
        override fun canInstallPackages() = install
        override fun urgentChannelBypassesDnd() = urgentChannel
    }

    @Test
    fun theUrgentChannelRowIsRedUntilTheChannelReallyBypassesDnd() {
        val probe = FakeProbe(urgentChannel = false)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.URGENT_CHANNEL, probe))
        probe.urgentChannel = true
        assertEquals(RowStatus.PASS, status(ChecklistItem.URGENT_CHANNEL, probe))
        probe.urgentChannel = null
        assertEquals(RowStatus.UNKNOWN, status(ChecklistItem.URGENT_CHANNEL, probe))
        // A failing row raises the badge but is never reported as a key of its own.
        assertTrue(SetupChecklist(FakeProbe(urgentChannel = false)).evaluate().hasFailures)
        assertFalse(SetupChecklist(FakeProbe(urgentChannel = false)).serverMap().keys.any { it.contains("urgent") })
    }

    @Test
    fun theRealProbeReadsTheUrgentChannelsBypassFlag() {
        notifications.deleteNotificationChannel("page_urgent")
        assertEquals(null, probe().urgentChannelBypassesDnd())
        com.example.ptt.alert.AlertChannels.create(app)
        assertEquals(true, probe().urgentChannelBypassesDnd())
        // What Android does when the flag was dropped at creation: the stored channel no longer bypasses.
        notifications.getNotificationChannel("page_urgent").setBypassDnd(false)
        assertEquals(false, probe().urgentChannelBypassesDnd())
    }

    @Test
    fun seeingDndAccessRecreatesTheChannelsAndNotSeeingItDoesNot() {
        var calls = 0
        // Building a checklist evaluates it once.
        SetupChecklist(FakeProbe(dnd = false), onDndAccess = { calls++ })
        SetupChecklist(FakeProbe(dnd = null), onDndAccess = { calls++ })
        assertEquals(0, calls)
        val checklist = SetupChecklist(FakeProbe(dnd = true), onDndAccess = { calls++ })
        assertEquals(1, calls)
        checklist.refresh()
        assertEquals(2, calls)
    }

    @Test
    fun theUrgentChannelRowLinksToThatChannelsSettings() {
        val intent = ChecklistLinks.intentFor(ChecklistItem.URGENT_CHANNEL, "com.example.ptt")
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("page_urgent", intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals("com.example.ptt", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun fullScreenRowFollowsItsProbe() {
        val probe = FakeProbe(fullScreen = false)
        assertEquals(RowStatus.FAIL, status(ChecklistItem.FULL_SCREEN, probe))
        probe.fullScreen = true
        assertEquals(RowStatus.PASS, status(ChecklistItem.FULL_SCREEN, probe))
        probe.fullScreen = null
        assertEquals(RowStatus.UNKNOWN, status(ChecklistItem.FULL_SCREEN, probe))
    }

    @Test
    fun anUnreadableStateIsNeitherGreenNorReportedAsTrue() {
        val checklist = SetupChecklist(FakeProbe(battery = null, notificationsEnabled = null))
        val state = checklist.evaluate()
        assertEquals(RowStatus.UNKNOWN, state.rows.first { it.item == ChecklistItem.BATTERY }.status)
        assertEquals(RowStatus.UNKNOWN, state.rows.first { it.item == ChecklistItem.NOTIFICATIONS }.status)
        assertFalse("battery" in state.serverMap())
        assertFalse("notifications" in state.serverMap())
        assertFalse("unknown rows do not raise the badge", state.hasFailures)
    }

    @Test
    fun oemRowIsEmptyForPixelsAndListsStepsForSamsungButIsNeverGreen() {
        val pixel = SetupChecklist(FakeProbe("google")).evaluate().rows.first {
            it.item == ChecklistItem.OEM_BACKGROUND
        }
        assertEquals(RowStatus.INFO, pixel.status)
        assertTrue(pixel.oem.isEmpty())
        val samsung = SetupChecklist(FakeProbe("samsung")).evaluate().rows.first {
            it.item ==
                ChecklistItem.OEM_BACKGROUND
        }
        assertEquals(RowStatus.UNKNOWN, samsung.status)
        assertTrue(samsung.oem.isNotEmpty())
        assertTrue(OemSettings.forManufacturer("Xiaomi").size > 1)
    }

    @Test
    fun allGoodHasNoFailureAndTheServerKeysAreStable() {
        val state = SetupChecklist(FakeProbe()).evaluate()
        assertFalse(state.hasFailures)
        // These key names are part of what the server stores; renaming one is a protocol change.
        assertEquals(
            setOf(
                "notifications",
                "lock_screen",
                "microphone",
                "full_screen",
                "dnd_access",
                "battery",
                "install_packages"
            ),
            state.serverMap().keys
        )
        assertTrue(state.serverMap().values.all { it })
        assertEquals(10, state.rows.size)
        assertEquals(RowStatus.INFO, state.rows.first { it.item == ChecklistItem.FOREGROUND_SERVICES }.status)
    }

    @Test
    fun aFailingRowRaisesTheBadgeAndIsReportedFalse() {
        val state = SetupChecklist(FakeProbe(battery = false, microphone = false)).evaluate()
        assertTrue(state.hasFailures)
        assertEquals(false, state.serverMap()["battery"])
        assertEquals(false, state.serverMap()["microphone"])
        assertEquals(true, state.serverMap()["dnd_access"])
    }

    @Test
    fun refreshPublishesTheLatestStateOnTheFlow() {
        val probe = FakeProbe()
        val checklist = SetupChecklist(probe)
        assertFalse(checklist.state.value.hasFailures)
        probe.battery = false
        checklist.refresh()
        assertTrue(checklist.state.value.hasFailures)
    }

    @Test
    fun everyRowHasADeepLink() {
        ChecklistItem.entries.forEach {
            val intent = ChecklistLinks.intentFor(it, "com.example.ptt")
            assertTrue(it.name, intent.action != null)
        }
        assertEquals(
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            ChecklistLinks.intentFor(ChecklistItem.DND_ACCESS, "com.example.ptt").action
        )
        assertEquals(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            ChecklistLinks.intentFor(ChecklistItem.INSTALL_PACKAGES, "com.example.ptt").action
        )
    }

    // ---- reported to the server only when it changes ----

    @Test
    fun theChecklistIsSentOnlyWhenItChanged() = runBlocking {
        val h = AdminHarness(tmp.newFolder())
        try {
            h.enrollAsBootstrapAdmin()
            val f = h.f
            f.fake.reply("PUT", "/v1/devices/me", 204)
            f.checklistMap = mapOf("battery" to false, "microphone" to true)
            assertTrue(f.registration.sync())
            assertEquals(
                """{"checklist":{"battery":false,"microphone":true}}""",
                f.fake.bodies("PUT", "/v1/devices/me").single()
            )
            // Same map: nothing is sent.
            assertTrue(f.registration.sync())
            assertEquals(1, f.fake.count("PUT", "/v1/devices/me"))
            // A change: the whole map is sent again.
            f.checklistMap = mapOf("battery" to true, "microphone" to true)
            assertTrue(f.registration.sync())
            assertEquals(
                """{"checklist":{"battery":true,"microphone":true}}""",
                f.fake.bodies("PUT", "/v1/devices/me").last()
            )
            // A failed PUT is retried next time.
            f.checklistMap = mapOf("battery" to false)
            f.fake.reply("PUT", "/v1/devices/me", 503)
            assertFalse(f.registration.sync())
            f.fake.reply("PUT", "/v1/devices/me", 204)
            assertTrue(f.registration.sync())
            assertEquals(4, f.fake.count("PUT", "/v1/devices/me"))
        } finally {
            h.f.close()
        }
    }

    // ---- lock screen and the secure-window effect ----

    @Test
    fun theLockStateFollowsTheKeyguard() {
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val lock = KeyguardLockState(app)
        shadowOf(keyguard).setKeyguardLocked(false)
        assertFalse(lock.isLocked())
        shadowOf(keyguard).setKeyguardLocked(true)
        assertTrue(lock.isLocked())
    }
}
