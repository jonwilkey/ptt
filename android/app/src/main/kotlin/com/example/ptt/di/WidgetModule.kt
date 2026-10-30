package com.example.ptt.di

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.ptt.data.ContactRepository
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.setup.SetupChecklist
import com.example.ptt.widget.FamilyWidget
import com.example.ptt.widget.WidgetStates
import com.example.ptt.widget.WidgetUpdater
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object WidgetModule {
    @Provides
    @Singleton
    fun widgetStates(
        rosters: RosterRepository,
        contacts: ContactRepository,
        settings: SettingsRepository,
        tokens: DeviceTokenStore
    ) = WidgetStates(rosters, contacts, settings, tokens)

    @Provides
    @Singleton
    fun widgetUpdater(
        @ApplicationContext context: Context,
        states: WidgetStates,
        checklist: SetupChecklist,
        settings: SettingsRepository,
        scope: CoroutineScope
    ) = WidgetUpdater(states, checklist, settings, scope) { FamilyWidget().updateAll(context) }
}
