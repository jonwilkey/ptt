package com.example.ptt.talk

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val PAGE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val OTHER = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test(timeout = 60_000)
    fun theAudioSourceFindsOnlyTheHostedPagesAudioAndFollowsTheService() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val source = ServiceTalkAudioSource()
        val seen = mutableListOf<TalkAudioPort?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.audios(PAGE).collect { seen += it } }
        assertEquals(listOf<TalkAudioPort?>(null), seen)

        val service = Robolectric.buildService(TalkService::class.java).create().get()
        val start = Intent(app, TalkService::class.java).putExtra(TalkIntents.EXTRA_PAGE_ID, PAGE)
        service.onStartCommand(start, 0, 1)
        val port = source.audio(PAGE)
        assertNotNull(port)
        assertSame("one port per audio", port, source.audio(PAGE))
        assertNull("another page's screen gets no audio", source.audio(OTHER))
        assertEquals(listOf(null, port), seen)

        service.onDestroy()
        assertNull(source.audio(PAGE))
        assertEquals(listOf(null, port, null), seen)
    }
}
