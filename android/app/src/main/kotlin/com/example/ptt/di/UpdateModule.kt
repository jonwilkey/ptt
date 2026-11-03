package com.example.ptt.di

import android.content.Context
import com.example.ptt.BuildConfig
import com.example.ptt.alert.AlertEngine
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.PttApi
import com.example.ptt.push.ActivePageRules
import com.example.ptt.push.SessionKeys
import com.example.ptt.talk.SessionStage
import com.example.ptt.talk.TalkSessions
import com.example.ptt.update.AndroidPackageFacts
import com.example.ptt.update.ApkInstaller
import com.example.ptt.update.InstallGate
import com.example.ptt.update.MinSupported
import com.example.ptt.update.PackageFacts
import com.example.ptt.update.SessionApkInstaller
import com.example.ptt.update.UpdateChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/** Wires the `update` package. */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    @Provides
    @Singleton
    fun minSupported(settings: SettingsRepository) = MinSupported(settings, BuildConfig.VERSION_CODE.toLong())

    @Provides
    @Singleton
    fun packageFacts(@ApplicationContext context: Context): PackageFacts = AndroidPackageFacts(context)

    @Provides
    @Singleton
    fun apkInstaller(@ApplicationContext context: Context): ApkInstaller = SessionApkInstaller(context)

    /** Busy while a page rings, a conversation is on, or the phone's one page slot holds a live page. */
    @Provides
    @Singleton
    fun installGate(
        engine: AlertEngine,
        sessions: TalkSessions,
        activePage: ActivePageRepository,
        sessionKeys: SessionKeys,
        clock: Clock
    ): InstallGate {
        val rules = ActivePageRules(engine, sessionKeys, clock)
        return InstallGate {
            val stage = sessions.state.value.stage
            engine.topRinging() != null ||
                stage == SessionStage.WAITING || stage == SessionStage.CONVERSATION ||
                activePage.current()?.let { !rules.isReplaceable(it, "", urgent = false) } == true
        }
    }

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun updateChecker(
        @ApplicationContext context: Context,
        api: PttApi,
        settings: SettingsRepository,
        packages: PackageFacts,
        installer: ApkInstaller,
        gate: InstallGate,
        clock: Clock
    ) = UpdateChecker(
        api,
        settings,
        packages,
        installer,
        gate,
        clock,
        File(context.noBackupFilesDir, "update"),
        Dispatchers.IO
    )
}
