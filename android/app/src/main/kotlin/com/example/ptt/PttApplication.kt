package com.example.ptt

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.ptt.alert.AlertChannels
import com.example.ptt.alert.AndroidAlarmVolume
import com.example.ptt.alert.SenderWaiting
import com.example.ptt.crypto.TinkKeys
import com.example.ptt.push.FirebaseInitializer
import com.example.ptt.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PttApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var senderWaiting: SenderWaiting

    @Inject
    lateinit var widgetUpdater: WidgetUpdater

    // WorkManager's default initializer is removed in the manifest so workers get Hilt injection.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Before anything parses a stored keyset (the key store also does this itself).
        TinkKeys.register()
        AlertChannels.create(this)
        // An alarm volume a crash or force-stop left raised for an urgent page goes back now.
        AndroidAlarmVolume(this).restoreLeftover()
        FirebaseInitializer.initialize(this)
        // The waiting notification of a page this phone sent follows its active-page row, from process start.
        senderWaiting.start()
        // The home-screen widget follows the roster, presence and the setup checklist (never incoming pages).
        widgetUpdater.start()
    }
}
