package com.example.ptt.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.ptt.BuildConfig
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.ContactRepository
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.AndroidDeviceTokenStore
import com.example.ptt.net.AuthInterceptor
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.EnrollmentState
import com.example.ptt.net.HttpClientFactory
import com.example.ptt.net.OkHttpPttApi
import com.example.ptt.net.PttApi
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxScheduler
import com.example.ptt.outbox.OutboxWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ptt_settings")

/** Wires the `net`, `data` and `outbox` packages. */
@Module
@InstallIn(SingletonComponent::class)
object NetDataModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Lenient about unknown fields in responses (a newer server may add some; the signed payloads, which
     * must be strict, are parsed by the crypto package, not here) and omits null request fields.
     */
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PttDatabase =
        Room.databaseBuilder(context, PttDatabase::class.java, PttDatabase.FILE_NAME).build()

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context.settingsDataStore)

    @Provides
    fun enrollmentState(settings: SettingsRepository): EnrollmentState = settings

    @Provides
    @Singleton
    fun deviceTokenStore(@ApplicationContext context: Context): DeviceTokenStore = AndroidDeviceTokenStore(context)

    @Provides
    @Singleton
    fun httpClient(tokens: DeviceTokenStore, state: EnrollmentState): OkHttpClient =
        HttpClientFactory.create(AuthInterceptor(tokens, state), BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun pttApi(client: OkHttpClient, state: EnrollmentState, json: Json): PttApi =
        OkHttpPttApi(client, state, json, Dispatchers.IO)

    @Provides
    @Singleton
    fun contacts(db: PttDatabase, clock: Clock) = ContactRepository(db.contactDao(), clock)

    @Provides
    @Singleton
    fun roster(db: PttDatabase, clock: Clock) = RosterRepository(db.rosterDao(), clock)

    @Provides
    @Singleton
    fun activePage(db: PttDatabase, clock: Clock) = ActivePageRepository(db.activePageDao(), clock)

    @Provides
    @Singleton
    fun outboxScheduler(@ApplicationContext context: Context): OutboxScheduler =
        OutboxWorker.WorkManagerScheduler(context)

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun outbox(
        db: PttDatabase,
        api: PttApi,
        json: Json,
        clock: Clock,
        scheduler: OutboxScheduler,
        scope: CoroutineScope
    ) = Outbox(db.outboxDao(), api, json, clock, scheduler, scope)
}
