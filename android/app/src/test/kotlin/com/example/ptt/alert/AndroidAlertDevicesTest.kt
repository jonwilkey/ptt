package com.example.ptt.alert

import android.app.Application
import android.app.NotificationManager
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidAlertDevicesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theVolumeToRestoreSurvivesAProcessRestartAndIsPutBackOnce() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val first = AndroidAlarmVolume(app)
        assertNull(first.pending)
        first.pending = first.current()
        first.set(first.max())
        assertEquals(first.max(), audio.getStreamVolume(AudioManager.STREAM_ALARM))
        // A new instance is what a restarted process sees.
        val second = AndroidAlarmVolume(app)
        assertEquals(2, second.pending)
        second.restorePending()
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(AndroidAlarmVolume(app).pending)
        // Nothing pending: restoring changes nothing.
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        AndroidAlarmVolume(app).restorePending()
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun aLeftoverVolumeIsRestoredOnlyWhileTheAlarmStillSitsAtMax() {
        val audio = app.getSystemService(AudioManager::class.java)
        val volume = AndroidAlarmVolume(app)
        volume.pending = 2
        audio.setStreamVolume(AudioManager.STREAM_ALARM, volume.max(), 0)
        AndroidAlarmVolume(app).restoreLeftover()
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(AndroidAlarmVolume(app).pending)
        volume.pending = 2
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 4, 0)
        AndroidAlarmVolume(app).restoreLeftover()
        assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(AndroidAlarmVolume(app).pending)
    }

    @Test
    fun theRingerIsQuietWhenSilentOrDoNotDisturbFilters() {
        val audio = app.getSystemService(AudioManager::class.java)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        val ringer = AndroidRingerState(app)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertFalse(ringer.quiet())
        // Vibrate mode: the notification vibrates by itself.
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertFalse(ringer.quiet())
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertTrue(ringer.quiet())
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        for (filter in listOf(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS
        )) {
            manager.setInterruptionFilter(filter)
            assertTrue("filter $filter", ringer.quiet())
        }
    }
}
