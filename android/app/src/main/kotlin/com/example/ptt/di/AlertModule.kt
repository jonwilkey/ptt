package com.example.ptt.di

import android.content.Context
import com.example.ptt.BuildConfig
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
import com.example.ptt.alert.SenderWaiting
import com.example.ptt.alert.WaitingNotices
import com.example.ptt.alert.WaitingNotifier
import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.DeviceTalkAuth
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.EnrollmentState
import com.example.ptt.net.OkHttpTalkTransport
import com.example.ptt.net.TalkAuth
import com.example.ptt.net.TalkTransport
import com.example.ptt.outbox.Outbox
import com.example.ptt.push.PageEvent
import com.example.ptt.push.PushDispatcher
import com.example.ptt.push.RosterSyncScheduler
import com.example.ptt.push.RosterSyncWorker
import com.example.ptt.push.SelfPages
import com.example.ptt.push.SessionKeys
import com.example.ptt.push.SocketPageEvents
import com.example.ptt.setup.RosterSync
import com.example.ptt.talk.AndroidTalkServiceControl
import com.example.ptt.talk.PageSender
import com.example.ptt.talk.ServiceTalkAudioSource
import com.example.ptt.talk.TalkAudioSource
import com.example.ptt.talk.TalkHosting
import com.example.ptt.talk.TalkServiceControl
import com.example.ptt.talk.TalkSessionController
import com.example.ptt.talk.TalkSessions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/** Wires the `push`, `alert` and sending (`talk`) packages. */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("LongParameterList")
object AlertModule {
    @Provides
    @Singleton
    fun sessionKeys() = SessionKeys()

    @Provides
    @Singleton
    fun selfPages() = SelfPages()

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
    fun waitingNotices(@ApplicationContext context: Context, hosting: TalkHosting): WaitingNotices =
        WaitingNotifier(context) { hosting.isHosting(it) }

    @Provides
    @Singleton
    fun talkHosting() = TalkHosting()

    @Provides
    @Singleton
    fun talkServiceControl(): TalkServiceControl = AndroidTalkServiceControl()

    @Provides
    @Singleton
    fun talkTransport(client: OkHttpClient, state: EnrollmentState): TalkTransport = OkHttpTalkTransport(client, state)

    @Provides
    @Singleton
    fun talkAuth(tokens: DeviceTokenStore, state: EnrollmentState): TalkAuth = DeviceTalkAuth(tokens, state)

    @Provides
    fun talkSessions(controller: TalkSessionController): TalkSessions = controller

    @Provides
    @Singleton
    fun talkAudioSource(): TalkAudioSource = ServiceTalkAudioSource()

    @Provides
    @Singleton
    fun socketPageEvents(dispatcher: PushDispatcher): SocketPageEvents = dispatcher

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun talkSessionController(
        transport: TalkTransport,
        auth: TalkAuth,
        sessionKeys: SessionKeys,
        activePage: ActivePageRepository,
        tokens: DeviceTokenStore,
        outbox: Outbox,
        events: SocketPageEvents,
        selfPages: SelfPages,
        service: TalkServiceControl,
        scope: CoroutineScope,
        clock: Clock
    ) = TalkSessionController(
        transport,
        auth,
        BuildConfig.VERSION_NAME,
        sessionKeys,
        activePage,
        tokens,
        outbox.outcomes,
        events,
        selfPages,
        service,
        scope,
        clock::millis
    )

    @Provides
    @Singleton
    fun senderWaiting(
        activePage: ActivePageRepository,
        rosterSync: RosterSync,
        tokens: DeviceTokenStore,
        outbox: Outbox,
        sessionKeys: SessionKeys,
        waiting: WaitingNotices,
        notices: PageNotices,
        clock: Clock,
        scope: CoroutineScope
    ) = SenderWaiting(activePage, rosterSync, tokens, outbox, sessionKeys, waiting, notices, clock, scope)

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
        clock: Clock,
        selfPages: SelfPages,
        hosting: TalkHosting
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
        clock,
        selfPages = selfPages,
        hosting = hosting
    )

    @Provides
    @Singleton
    fun pageSender(
        rosterSync: RosterSync,
        tokens: DeviceTokenStore,
        keyStore: DeviceKeyStore,
        outbox: Outbox,
        activePage: ActivePageRepository,
        sessionKeys: SessionKeys,
        engine: AlertEngine,
        notices: PageNotices,
        selfPages: SelfPages,
        clock: Clock
    ) = PageSender(rosterSync, tokens, keyStore, outbox, activePage, sessionKeys, engine, notices, selfPages, clock)

    /** What the talk screen listens to for a page ending. */
    @Provides
    fun pageEvents(dispatcher: PushDispatcher): SharedFlow<PageEvent> = dispatcher.events
}
