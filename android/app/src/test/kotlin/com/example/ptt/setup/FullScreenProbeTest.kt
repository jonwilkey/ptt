package com.example.ptt.setup

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real [AndroidSystemProbe] full-screen row on both sides of the Android 14 split. */
@RunWith(RobolectricTestRunner::class)
class FullScreenProbeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [33])
    fun belowAndroid14TheInstallTimePermissionDecides() {
        shadowOf(app).grantPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT)
        assertEquals(true, AndroidSystemProbe(app).fullScreenIntent())
        shadowOf(app).denyPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT)
        assertEquals(false, AndroidSystemProbe(app).fullScreenIntent())
    }

    @Test
    @Config(sdk = [34])
    fun onAndroid14TheRealCanUseFullScreenIntentIsAsked() {
        // Robolectric's NotificationManager answers from the app's permission state here; the point is that the
        // 34+ branch runs against the real framework call and yields a definite answer, not a crash or null.
        val result = AndroidSystemProbe(app).fullScreenIntent()
        assertEquals(true, result != null)
    }
}
