package com.example.ptt.widget

import android.util.Log
import com.example.ptt.data.SettingsRepository
import com.example.ptt.setup.SetupChecklist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the home-screen widget current. Started from the application, like `SenderWaiting`:
 * - records each checklist result (all fine / has a red row) in the settings, so the widget can show its badge
 *   after the process died, and
 * - re-renders the widget when what it shows changes (roster, presence, enrolment, the badge), at most once per
 *   [DEBOUNCE_MS] burst. It never reacts to incoming pages.
 */
class WidgetUpdater(
    private val states: WidgetStates,
    private val checklist: SetupChecklist,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val refresh: suspend () -> Unit
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            checklist.state.map { it.hasFailures }.distinctUntilChanged().collect {
                safely("record checklist") { settings.setChecklistRegressed(it) }
            }
        }
        scope.launch {
            // The first emission redraws once after every process start. That is intended: it starts a widget
            // session if none is running and repairs a stale picture; it never shows or reacts to incoming pages.
            states.states.debounce(DEBOUNCE_MS).collect { safely("redraw") { refresh() } }
        }
    }

    /** This runs from every process start (including FCM-woken ones): a failure here must not crash the app. */
    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Log.w(TAG, "widget $what failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEBOUNCE_MS = 500L
        private const val TAG = "WidgetUpdater"
    }
}
