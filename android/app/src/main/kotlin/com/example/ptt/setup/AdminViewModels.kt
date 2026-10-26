package com.example.ptt.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ptt.crypto.EnrolledDevice
import com.example.ptt.crypto.EnrollmentRejection
import com.example.ptt.crypto.ProtocolException
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDevice
import com.example.ptt.data.RosterEntity
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.DeviceTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The verified roster as the admin screens need it. [selfId] is this phone's device id. */
class RosterView(val roster: Roster, val selfId: String?) {
    val isAdmin: Boolean get() = selfId != null && roster.find(selfId)?.admin == true

    /** One entry per person, in roster order: the first device's name and admin flag. */
    val people: List<RosterDevice> get() = roster.devices.distinctBy { it.userId }
}

/** Parses the stored (already verified) roster row; null when absent or unreadable. */
internal fun RosterEntity?.toRoster(): Roster? = this?.let {
    try {
        Roster.parse(it.parsedJson.toByteArray(Charsets.UTF_8))
    } catch (_: ProtocolException) {
        null
    }
}

/** What the Add-device screen shows. The QR text holds the enrollment secret: it lives in this state only. */
sealed interface AddDeviceUi {
    data object Form : AddDeviceUi

    data object Starting : AddDeviceUi

    /** Never put this in saved state, an Intent or a log. */
    class ShowingQr(val qrText: String) : AddDeviceUi {
        override fun toString() = "ShowingQr(<redacted>)"
    }

    data object Signing : AddDeviceUi

    class Done(val displayName: String) : AddDeviceUi

    data object Expired : AddDeviceUi

    /** The server's phone did not check out: nothing was signed, and this enrollment is over. */
    class Rejected(val reason: EnrollmentRejection) : AddDeviceUi

    /** [retry] is true when signing can simply be tried again (the new phone's check already passed). */
    class Failed(val error: AddDeviceError, val retry: Boolean) : AddDeviceUi
}

/**
 * Drives the Add-device screen. The pending enrollment (secret and code) is a plain field of this
 * ViewModel: never a SavedStateHandle entry, never persisted.
 *
 * The enrollment is NOT abandoned when the activity stops (screen timeout, app switch, rotation): the
 * code is single-use, so abandoning it would strand both phones. Polling simply pauses while the screen
 * is not started ([pollLoop] runs inside `repeatOnLifecycle(STARTED)`), and signing runs in
 * `viewModelScope` so it survives a stop. [abandon] is for an explicit leave (Cancel, Back, a route
 * change, losing admin); expiry and [onCleared] also end it. Abandoning during Signing waits for the
 * signing attempt to finish (cancelling a PUT mid-flight could leave the server and phone out of step).
 */
// One small function per user action and lifecycle step.
@Suppress("TooManyFunctions")
@HiltViewModel
class AddDeviceViewModel @Inject constructor(
    private val flow: AddDeviceFlow,
    rosters: RosterRepository,
    private val tokens: DeviceTokenStore
) : ViewModel() {
    private val _ui = MutableStateFlow<AddDeviceUi>(AddDeviceUi.Form)
    val ui: StateFlow<AddDeviceUi> = _ui

    val people: Flow<List<RosterDevice>> =
        rosters.roster.map { entity -> entity.toRoster()?.let { RosterView(it, tokens.deviceId()).people }.orEmpty() }

    private var pending: PendingEnrollment? = null
    private var used: EnrolledDevice? = null
    private var startJob: Job? = null
    private var signJob: Job? = null
    private var abandonAfterSigning = false

    fun start(target: AddTarget) {
        if (_ui.value == AddDeviceUi.Starting || _ui.value is AddDeviceUi.ShowingQr) return
        if (_ui.value == AddDeviceUi.Signing) return
        endNow()
        _ui.value = AddDeviceUi.Starting
        startJob = launchSafely {
            when (val result = flow.start(target)) {
                is StartResult.Failed -> _ui.value = AddDeviceUi.Failed(result.error, retry = false)

                is StartResult.Started -> {
                    pending = result.enrollment
                    _ui.value = AddDeviceUi.ShowingQr(result.enrollment.qrText)
                }
            }
        }
    }

    /** Polls every 2 s until cancelled. The screen runs it only while STARTED. */
    suspend fun pollLoop(intervalMs: Long = POLL_INTERVAL_MS) {
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            pollOnce()
        }
    }

    // Each terminal poll result ends the enrollment at its own step.
    @Suppress("ReturnCount")
    internal suspend fun pollOnce() {
        val enrollment = pending ?: return
        if (_ui.value !is AddDeviceUi.ShowingQr) return
        val polled = flow.poll(enrollment)
        // The enrollment may have been abandoned while the request was in flight.
        if (pending !== enrollment || _ui.value !is AddDeviceUi.ShowingQr) return
        when (polled) {
            PollResult.Pending, PollResult.Transient -> Unit

            PollResult.Expired -> finish(enrollment, AddDeviceUi.Expired)

            PollResult.NotEnrolled -> finish(enrollment, AddDeviceUi.Failed(AddDeviceError.NOT_ENROLLED, false))

            PollResult.ServerRefused ->
                finish(enrollment, AddDeviceUi.Failed(AddDeviceError.REJECTED_BY_SERVER, false))

            is PollResult.Rejected -> finish(enrollment, AddDeviceUi.Rejected(polled.reason))

            is PollResult.Used -> {
                used = polled.device
                beginSigning(enrollment, polled.device)
            }
        }
    }

    private fun beginSigning(enrollment: PendingEnrollment, device: EnrolledDevice) {
        _ui.value = AddDeviceUi.Signing
        signJob = launchSafely { sign(enrollment, device) }
    }

    private suspend fun sign(enrollment: PendingEnrollment, device: EnrolledDevice) {
        when (val result = flow.complete(enrollment, device)) {
            is CompleteResult.Done -> finish(enrollment, AddDeviceUi.Done(result.device.displayName))

            is CompleteResult.Rejected -> finish(enrollment, AddDeviceUi.Rejected(result.reason))

            // The new phone passed (or the failure was not about it): signing can be retried.
            is CompleteResult.Failed -> {
                _ui.value = AddDeviceUi.Failed(result.error, retry = result.error.canRetrySigning())
                if (abandonAfterSigning) endNow()
            }
        }
    }

    /** Signing failed for a retryable reason (network, stale roster, locked phone): sign again. */
    fun retrySigning() {
        val enrollment = pending
        val device = used
        val failed = _ui.value as? AddDeviceUi.Failed
        if (enrollment != null && device != null && failed?.retry == true) beginSigning(enrollment, device)
    }

    /** Ends the enrollment (zeroes the secret; the code is left to expire), after a running signing finishes. */
    fun abandon() {
        if (_ui.value == AddDeviceUi.Signing && signJob?.isActive == true) {
            abandonAfterSigning = true
        } else {
            endNow()
        }
    }

    private fun endNow() {
        startJob?.cancel()
        signJob?.cancel()
        startJob = null
        signJob = null
        abandonAfterSigning = false
        pending?.abandon()
        pending = null
        used = null
        if (_ui.value !is AddDeviceUi.Form) _ui.value = AddDeviceUi.Form
    }

    private fun finish(enrollment: PendingEnrollment, terminal: AddDeviceUi) {
        enrollment.abandon()
        pending = null
        used = null
        abandonAfterSigning = false
        _ui.value = terminal
    }

    override fun onCleared() = endNow()

    private fun launchSafely(block: suspend () -> Unit): Job {
        val handler = CoroutineExceptionHandler { _, e ->
            android.util.Log.w("AddDeviceViewModel", "add device failed: ${e.javaClass.simpleName}")
            pending?.let { finish(it, AddDeviceUi.Failed(AddDeviceError.INVALID_ROSTER, retry = false)) }
                ?: run { _ui.value = AddDeviceUi.Failed(AddDeviceError.INVALID_ROSTER, retry = false) }
        }
        return viewModelScope.launch(handler) { block() }
    }

    private fun AddDeviceError.canRetrySigning() =
        this == AddDeviceError.NETWORK || this == AddDeviceError.STALE || this == AddDeviceError.LOCKED

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}

data class DevicesUi(val busy: Boolean = false, val message: DevicesMessage? = null)

enum class DevicesMessage { REMOVED, LOCKED, SELF, LAST_ADMIN, UNKNOWN, NOT_ADMIN, FAILED, SERVER_REFUSED }

/** Drives Settings > Devices: the roster list, removal, and the revocation-pending retries. */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val revoker: DeviceRevoker,
    rosters: RosterRepository,
    settings: SettingsRepository,
    private val tokens: DeviceTokenStore
) : ViewModel() {
    private val _ui = MutableStateFlow(DevicesUi())
    val ui: StateFlow<DevicesUi> = _ui

    val view: Flow<RosterView?> = rosters.roster.map { entity ->
        entity.toRoster()?.let { RosterView(it, tokens.deviceId()) }
    }

    /** Ids whose revoke is outstanding (their device is already out of the roster). */
    val pending: Flow<Set<String>> = combine(settings.pendingRevocationsFlow, rosters.roster) { ids, entity ->
        val roster = entity.toRoster()
        if (roster == null) emptySet() else ids.filter { roster.find(it) == null }.toSet()
    }

    fun remove(deviceId: String) {
        if (_ui.value.busy) return
        _ui.value = DevicesUi(busy = true)
        viewModelScope.launch(handler()) {
            _ui.value = DevicesUi(message = messageFor(revoker.remove(deviceId)))
        }
    }

    fun dismissMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    /**
     * Retries outstanding revokes with a growing pause for as long as it is running, and checks for new
     * ones every few seconds when none is outstanding. It suspends until cancelled, so the screen runs it
     * inside `repeatOnLifecycle(STARTED)` and it really runs only while visible.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun retryLoop(firstMs: Long = FIRST_RETRY_MS, maxMs: Long = MAX_RETRY_MS, idleMs: Long = IDLE_MS) {
        var pause = firstMs
        while (currentCoroutineContext().isActive) {
            try {
                if (revoker.outstanding().isEmpty()) {
                    pause = firstMs
                    delay(idleMs)
                } else {
                    revoker.retryPending()
                    delay(pause)
                    pause = (pause * 2).coerceAtMost(maxMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("DevicesViewModel", "revoke retry failed: ${e.javaClass.simpleName}")
                delay(maxMs)
            }
        }
    }

    fun retryNow() {
        viewModelScope.launch(handler()) { revoker.retryPending() }
    }

    internal fun messageFor(result: RevokeResult): DevicesMessage? = when (result) {
        RevokeResult.Done -> DevicesMessage.REMOVED

        RevokeResult.RevocationPending -> null

        RevokeResult.ServerRefusedRevoke -> DevicesMessage.SERVER_REFUSED

        is RevokeResult.Refused -> when (result.reason) {
            RevokeRefusal.LOCKED -> DevicesMessage.LOCKED
            RevokeRefusal.SELF -> DevicesMessage.SELF
            RevokeRefusal.LAST_ADMIN -> DevicesMessage.LAST_ADMIN
            RevokeRefusal.UNKNOWN_DEVICE -> DevicesMessage.UNKNOWN
            RevokeRefusal.NOT_ADMIN -> DevicesMessage.NOT_ADMIN
        }

        is RevokeResult.Failed -> DevicesMessage.FAILED
    }

    private fun handler() = CoroutineExceptionHandler { _, e ->
        android.util.Log.w("DevicesViewModel", "device action failed: ${e.javaClass.simpleName}")
        _ui.value = DevicesUi(message = DevicesMessage.FAILED)
    }

    private companion object {
        const val FIRST_RETRY_MS = 3_000L
        const val MAX_RETRY_MS = 60_000L
        const val IDLE_MS = 5_000L
    }
}
