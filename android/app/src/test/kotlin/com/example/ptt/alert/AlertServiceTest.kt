package com.example.ptt.alert

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlertServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theServiceGoesForegroundAtOnceOnItsOwnQuietChannelAndLeavesWhenNothingRings() {
        val controller = Robolectric.buildService(AlertService::class.java, Intent(app, AlertService::class.java))
        val service = controller.create().get()
        val result = service.onStartCommand(Intent(app, AlertService::class.java), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(AlertChannels.SERVICE, notification.channelId)
        assertEquals(AlertService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        // Nothing is ringing in this fresh process, so the service asked to stop itself.
        assertTrue(shadow.isStoppedBySelf)
    }

    @Test
    fun destroyingTheServiceIsSafeWhenNothingRang() {
        val service = Robolectric.buildService(AlertService::class.java).create().get()
        service.onDestroy()
    }

    @Test
    fun theServiceIsVisibleToTheEngineOnlyOnceItIsForegroundAndCanRefreshItsNotice() {
        val service = Robolectric.buildService(AlertService::class.java).create().get()
        // Created but not yet foreground: the engine cannot see it, so it cannot stop it early.
        assertNotSame(service, AlertService.current)
        service.onStartCommand(Intent(app, AlertService::class.java), 0, 1)
        assertSame(service, AlertService.current)
        service.showNotice(null)
        assertNotNull(
            shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
                .getNotification(AlertService.NOTIFICATION_ID)
        )
        service.onDestroy()
        assertNull(AlertService.current)
    }
}
