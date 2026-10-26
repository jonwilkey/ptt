package com.example.ptt

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.ptt.alert.AlertChannels
import com.example.ptt.alert.AndroidAlarmVolume
import com.example.ptt.push.FirebaseInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PttApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // WorkManager's default initializer is removed in the manifest so workers get Hilt injection.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        AlertChannels.create(this)
        // An alarm volume a crash or force-stop left raised for an urgent page goes back now.
        AndroidAlarmVolume(this).restoreLeftover()
        FirebaseInitializer.initialize(this)
    }
}
