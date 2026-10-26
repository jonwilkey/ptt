package com.example.ptt.di

import android.content.Context
import com.example.ptt.alert.AlertDevices
import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.AlertServiceControl
import com.example.ptt.alert.AndroidAlarmVolume
import com.example.ptt.alert.AndroidAlertServiceControl
import com.example.ptt.alert.AndroidAlertSound
import com.example.ptt.alert.AndroidAlertVibrator
import com.example.ptt.alert.AndroidAlertWakeLock
import com.example.ptt.alert.AndroidRingerState
import com.example.ptt.alert.PageNotices
import com.example.ptt.alert.PageNotifier
import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.outbox.Outbox
import com.example.ptt.push.PushDispatcher
import com.example.ptt.push.RosterSyncScheduler
import com.example.ptt.push.RosterSyncWorker
import com.example.ptt.push.SessionKeys
import com.example.ptt.setup.RosterSync
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Wires the `push` and `alert` packages. */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("LongParameterList")
object AlertModule {
    @Provides
    @Singleton
    fun sessionKeys() = SessionKeys()

    @Provides
    @Singleton
    fun pageNotices(@ApplicationContext context: Context): PageNotices = PageNotifier(context)

    @Provides
    @Singleton
    fun alertServiceControl(@ApplicationContext context: Context): AlertServiceControl =
        AndroidAlertServiceControl(context)

    @Provides
    @Singleton
    fun alertDevices(@ApplicationContext context: Context) = AlertDevices(
        AndroidAlertSound(context),
        AndroidAlertVibrator(context),
        AndroidAlarmVolume(context),
        AndroidAlertWakeLock(context),
        AndroidRingerState(context)
    )

    @Provides
    @Singleton
    fun alertEngine(
        scope: CoroutineScope,
        devices: AlertDevices,
        notices: PageNotices,
        service: AlertServiceControl,
        outbox: Outbox,
        activePage: ActivePageRepository,
        sessionKeys: SessionKeys,
        settings: SettingsRepository
    ) = AlertEngine(
        scope,
        devices,
        notices,
        service,
        outbox,
        activePage,
        sessionKeys,
        // A quick local read, taken once each time an urgent page starts ringing.
        maxVolumeEnabled = { runBlocking { settings.urgentAlertMaxVolume.first() } }
    )

    @Provides
    @Singleton
    fun rosterSyncScheduler(@ApplicationContext context: Context): RosterSyncScheduler =
        RosterSyncWorker.WorkManagerScheduler(context)

    @Provides
    @Singleton
    fun pushDispatcher(
        rosterSync: RosterSync,
        tokens: DeviceTokenStore,
        keyStore: DeviceKeyStore,
        outbox: Outbox,
        engine: AlertEngine,
        notices: PageNotices,
        activePage: ActivePageRepository,
        sessionKeys: SessionKeys,
        scheduler: RosterSyncScheduler,
        clock: Clock
    ) = PushDispatcher(
        rosterSync,
        tokens,
        keyStore,
        outbox,
        engine,
        notices,
        activePage,
        sessionKeys,
        scheduler,
        clock
    )
}
