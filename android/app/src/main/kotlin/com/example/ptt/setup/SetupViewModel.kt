package com.example.ptt.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptt.data.DeviceProfile
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EnrollUiState(val busy: Boolean = false, val error: EnrollError? = null, val scanFailed: Boolean = false)

data class HomeInfo(val profile: DeviceProfile, val serverUrl: String?, val rosterVersion: Long?)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val startup: AppStartup,
    private val enroller: EnrollmentController,
    private val rosterSync: RosterSync,
    private val registration: DeviceRegistration,
    settings: SettingsRepository,
    rosters: RosterRepository
) : ViewModel() {
    private val _route = MutableStateFlow<Route?>(null)

    /** Null until the first routing decision. */
    val route: StateFlow<Route?> = _route

    private val _enrollState = MutableStateFlow(EnrollUiState())
    val enrollState: StateFlow<EnrollUiState> = _enrollState

    val home: Flow<HomeInfo?> = combine(settings.profileFlow, settings.serverUrlFlow, rosters.roster) { p, url, r ->
        p?.let { HomeInfo(it, url, r?.version) }
    }

    /** App start and each time the activity comes back: route, then sync in the background. */
    fun onStart() {
        launchSafely {
            _route.value = startup.resolve()
            if (_route.value !is Route.Enroll) {
                syncNow()
                registration.sync()
            }
        }
    }

    /** One roster sync (the waiting screen calls this every 5 s and from its retry button). */
    fun syncNow() {
        launchSafely {
            rosterSync.sync()
            _route.value = startup.resolve()
        }
    }

    /** Runs [block] so that no exception can crash the app; only the exception class is logged. */
    private fun launchSafely(block: suspend () -> Unit) {
        val handler = CoroutineExceptionHandler { _, e ->
            android.util.Log.w("SetupViewModel", "background step failed: ${e.javaClass.simpleName}")
        }
        viewModelScope.launch(handler) { block() }
    }

    fun scanFailed() = _enrollState.update { it.copy(scanFailed = true) }

    // Not saved across rotation: if the activity is recreated while the scanner is open, the scan result is
    // dropped and the user scans again (a known, accepted limitation).
    @Suppress("TooGenericExceptionCaught")
    fun enroll(qrText: String, deviceName: String) {
        if (_enrollState.value.busy) return
        _enrollState.value = EnrollUiState(busy = true)
        launchSafely {
            val result = try {
                enroller.enroll(qrText, deviceName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // enroll() has already wiped local state on its way out.
                android.util.Log.w("SetupViewModel", "enrollment failed: ${e.javaClass.simpleName}")
                EnrollResult.Failure(EnrollError.OTHER)
            }
            when (result) {
                is EnrollResult.Success -> {
                    _enrollState.value = EnrollUiState()
                    _route.value = startup.resolve()
                    registration.sync()
                }

                is EnrollResult.Failure -> {
                    _enrollState.value = EnrollUiState(error = result.error)
                    // An unexpected failure after the server enrolled the phone leaves it enrolled: route on.
                    _route.value = startup.resolve()
                }
            }
        }
    }
}
