package com.example.ptt.di

import android.content.Context
import com.example.ptt.BuildConfig
import com.example.ptt.crypto.AndroidDeviceKeyStore
import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.ContactRepository
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.PttApi
import com.example.ptt.outbox.Outbox
import com.example.ptt.setup.AppStartup
import com.example.ptt.setup.DeviceRegistration
import com.example.ptt.setup.EnrollmentController
import com.example.ptt.setup.FcmTokenSource
import com.example.ptt.setup.FirebaseFcmTokenSource
import com.example.ptt.setup.LocalStateWiper
import com.example.ptt.setup.RosterSync
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/** Wires the `setup` package. */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("LongParameterList")
object SetupModule {
    @Provides
    @Singleton
    fun deviceKeyStore(@ApplicationContext context: Context): DeviceKeyStore = AndroidDeviceKeyStore(context)

    @Provides
    @Singleton
    fun fcmTokenSource(@ApplicationContext context: Context): FcmTokenSource = FirebaseFcmTokenSource(context)

    @Provides
    @Singleton
    fun wiper(
        settings: SettingsRepository,
        tokens: DeviceTokenStore,
        rosters: RosterRepository,
        contacts: ContactRepository,
        activePage: ActivePageRepository,
        db: PttDatabase,
        keys: DeviceKeyStore
    ) = LocalStateWiper(settings, tokens, rosters, contacts, activePage, db.outboxDao(), keys)

    @Provides
    @Singleton
    fun rosterSync(
        api: PttApi,
        rosters: RosterRepository,
        contacts: ContactRepository,
        settings: SettingsRepository,
        tokens: DeviceTokenStore,
        keys: DeviceKeyStore,
        clock: Clock
    ) = RosterSync(api, rosters, contacts, settings, tokens, keys, clock)

    @Provides
    @Singleton
    fun deviceRegistration(api: PttApi, settings: SettingsRepository, fcm: FcmTokenSource) =
        DeviceRegistration(api, settings, fcm, BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    fun appStartup(
        settings: SettingsRepository,
        tokens: DeviceTokenStore,
        keys: DeviceKeyStore,
        rosters: RosterRepository,
        wiper: LocalStateWiper
    ) = AppStartup(settings, tokens, keys, rosters, wiper)

    @Provides
    @Singleton
    fun enrollmentController(
        api: PttApi,
        keys: DeviceKeyStore,
        tokens: DeviceTokenStore,
        settings: SettingsRepository,
        wiper: LocalStateWiper,
        rosterSync: RosterSync,
        fcm: FcmTokenSource,
        outbox: Outbox
    ) = EnrollmentController(
        api, keys, tokens, settings, wiper, rosterSync, fcm,
        appVersion = BuildConfig.VERSION_NAME,
        debug = BuildConfig.DEBUG,
        resumeOutbox = outbox::resume
    )
}
