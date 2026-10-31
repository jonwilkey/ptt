package com.example.ptt.talk

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val PAGE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

@RunWith(RobolectricTestRunner::class)
class TalkServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test(timeout = 60_000)
    fun manifestDeclaresAMicrophoneForegroundService() {
        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS
        )
        val service = info.services!!.single { it.name == TalkService::class.java.name }
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        assertFalse("not exported", service.exported)
        val permissions = info.requestedPermissions!!.toList()
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test(timeout = 60_000)
    fun notStartedWithoutRecordAudio() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(TalkService.canStart(app))
        assertFalse(TalkService.start(app, PAGE, "Lee"))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test(timeout = 60_000)
    fun startedWithRecordAudioCarryingThePage() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(TalkService.start(app, PAGE, "Lee"))
        val intent = shadowOf(app).nextStartedService
        assertNotNull(intent)
        assertEquals(TalkService::class.java.name, intent.component?.className)
        assertEquals(PAGE, intent.getStringExtra(TalkIntents.EXTRA_PAGE_ID))
    }
}
