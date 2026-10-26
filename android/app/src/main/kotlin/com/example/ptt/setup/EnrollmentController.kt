package com.example.ptt.setup

import android.util.Log
import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.EnrollmentMac
import com.example.ptt.crypto.EnrollmentQr
import com.example.ptt.crypto.ProtocolException
import com.example.ptt.crypto.TrustedAdmin
import com.example.ptt.crypto.Validation
import com.example.ptt.data.DeviceProfile
import com.example.ptt.data.RegistrationValues
import com.example.ptt.data.ServerUrl
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.ApiResult
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.DeviceTokenUnavailable
import com.example.ptt.net.EnrollRequest
import com.example.ptt.net.EnrollResponse
import com.example.ptt.net.PttApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class EnrollError {
    /** The scanned text is not an enrollment QR for an acceptable server. */
    INVALID_QR,
    INVALID_NAME,

    /** `code_invalid`: unknown, used or expired (the server does not say which). */
    CODE_INVALID,

    /** HTTP 429. */
    RATE_LIMITED,

    /** Network failure or a 5xx. */
    NETWORK,

    /** A bootstrap phone must become an admin; the server said otherwise. */
    NOT_ADMIN,

    /** The Keystore could not store the device keys or token. */
    KEYS_UNAVAILABLE,
    OTHER
}

sealed interface EnrollResult {
    data object Success : EnrollResult

    data class Failure(val error: EnrollError) : EnrollResult
}

/**
 * Enrolls this phone from a scanned QR (docs/protocol.md section 5, flow step 3 and the bootstrap
 * phone). UI-free. Everything local is wiped before a new enrollment starts, and again if it fails after
 * the keys exist, so there is never a half-enrolled state.
 */
@Suppress("LongParameterList")
class EnrollmentController(
    private val api: PttApi,
    private val keyStore: DeviceKeyStore,
    private val tokens: DeviceTokenStore,
    private val settings: SettingsRepository,
    private val wiper: LocalStateWiper,
    private val rosterSync: RosterSync,
    private val fcm: FcmTokenSource,
    private val appVersion: String,
    private val debug: Boolean,
    private val resumeOutbox: () -> Unit,
    private val newDeviceId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val bootstrapRetryDelayMs: Long = BOOTSTRAP_RETRY_DELAY_MS,
    private val log: (String) -> Unit = { Log.w(TAG, it) }
) {
    @Suppress("ReturnCount")
    suspend fun enroll(qrText: String, deviceNameInput: String): EnrollResult {
        // Validate everything that needs no state change first, so a typo does not wipe a working setup.
        val qr = try {
            EnrollmentQr.parse(qrText.trim(), allowDevHttp = debug)
        } catch (_: ProtocolException) {
            return EnrollResult.Failure(EnrollError.INVALID_QR)
        }
        val serverUrl = ServerUrl.normalize(qr.url, debug) ?: return EnrollResult.Failure(EnrollError.INVALID_QR)
        val deviceName = deviceNameInput.trim()
        if (!Validation.isValidText(deviceName)) return EnrollResult.Failure(EnrollError.INVALID_NAME)

        // The shared local-state lock is held from the first wipe until the enrollment is stored (or wiped), so
        // AppStartup.resolve() cannot wipe keys or the token between our key creation and the server call
        // (the scanner is a separate activity, so ON_START and the scan result arrive together). The lock is
        // released before the network-heavy follow-up; RosterSync has its own mutex and never takes this one.
        val result = wiper.lock.withLock {
            wiper.wipeAll()
            val progress = Progress()
            try {
                register(qr, serverUrl, deviceName, progress)
            } finally {
                // Any failure (or cancellation) before the enrollment is stored leaves nothing behind. Once the
                // server has enrolled the device and it is stored, it stays enrolled whatever happens later
                // (a bootstrap code is single-use, so wiping then would lock the family out).
                if (!progress.stored) withContext(NonCancellable) { wiper.wipeAll() }
            }
        }
        if (result is EnrollResult.Success) afterEnrollment(qr)
        return result
    }

    private class Progress {
        var stored = false
    }

    @Suppress("ReturnCount")
    private suspend fun register(
        qr: EnrollmentQr,
        serverUrl: String,
        deviceName: String,
        progress: Progress
    ): EnrollResult {
        val deviceId = newDeviceId()
        val keys = try {
            DeviceKeys.create(keyStore)
        } catch (_: com.example.ptt.crypto.DeviceKeysUnavailable) {
            return EnrollResult.Failure(EnrollError.KEYS_UNAVAILABLE)
        }
        val fcmToken = fcm.token()
        val mac = qr.secret?.let { EnrollmentMac.compute(it, qr.code, deviceId, keys.ed25519Public, keys.x25519Public) }
        val request = EnrollRequest(
            code = B64u.encode(qr.code),
            deviceId = deviceId,
            deviceName = deviceName,
            ed25519 = B64u.encode(keys.ed25519Public),
            x25519 = B64u.encode(keys.x25519Public),
            mac = mac?.let { B64u.encode(it) },
            fcmToken = fcmToken,
            appVersion = appVersion
        )
        val response = when (val result = api.enroll(serverUrl, request)) {
            is ApiResult.Success -> result.body
            else -> return EnrollResult.Failure(errorFor(result))
        }
        if (response.deviceId != deviceId || response.token.isEmpty()) return EnrollResult.Failure(EnrollError.OTHER)
        // The server makes a bootstrap phone an admin; anything else cannot publish roster v1.
        if (qr.isBootstrap && !response.admin) return EnrollResult.Failure(EnrollError.NOT_ADMIN)

        val anchor = if (qr.isBootstrap) {
            listOf(TrustedAdmin(deviceId, keys.ed25519Public))
        } else {
            qr.admins
        }
        try {
            store(
                response,
                deviceName,
                serverUrl,
                anchor,
                RegistrationValues(appVersion, deviceName, fcmToken?.let { DeviceRegistration.hashOf(it) })
            )
        } catch (_: DeviceTokenUnavailable) {
            return EnrollResult.Failure(EnrollError.KEYS_UNAVAILABLE)
        }
        progress.stored = true
        return EnrollResult.Success
    }

    /**
     * Everything after the enrollment is stored is best effort: a failure leaves the phone enrolled and
     * routes it to the waiting screen, whose sync tries again. Only the exception class is logged.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun afterEnrollment(qr: EnrollmentQr) {
        try {
            resumeOutbox()
            if (qr.isBootstrap) publishBootstrapWithRetries() else rosterSync.sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("post-enrollment step failed: ${e.javaClass.simpleName}")
        }
    }

    // The token and profile go in before the "enrolled" flag, which the API layer checks last.
    private suspend fun store(
        response: EnrollResponse,
        deviceName: String,
        serverUrl: String,
        anchor: List<TrustedAdmin>,
        registered: RegistrationValues
    ) {
        tokens.save(response.deviceId, response.token)
        settings.saveProfile(DeviceProfile(response.userId, response.displayName, response.admin, deviceName))
        settings.setTrustAnchor(anchor)
        settings.setLastRegistration(registered)
        settings.setEnrolled(serverUrl)
    }

    /** Retries the PUT on network failure; if it keeps failing, the waiting screen's sync tries again later. */
    private suspend fun publishBootstrapWithRetries() {
        repeat(BOOTSTRAP_ATTEMPTS) { attempt ->
            if (rosterSync.publishBootstrap() != SyncResult.Unavailable) return
            if (attempt < BOOTSTRAP_ATTEMPTS - 1) delay(bootstrapRetryDelayMs)
        }
    }

    private fun errorFor(result: ApiResult<*>): EnrollError = when (result) {
        is ApiResult.Final -> if (result.code == CODE_INVALID) EnrollError.CODE_INVALID else EnrollError.OTHER

        is ApiResult.Retryable -> if (result.status ==
            STATUS_TOO_MANY_REQUESTS
        ) {
            EnrollError.RATE_LIMITED
        } else {
            EnrollError.NETWORK
        }

        else -> EnrollError.OTHER
    }

    private companion object {
        const val TAG = "EnrollmentController"
        const val CODE_INVALID = "code_invalid"
        const val STATUS_TOO_MANY_REQUESTS = 429
        const val BOOTSTRAP_ATTEMPTS = 3
        const val BOOTSTRAP_RETRY_DELAY_MS = 2_000L
    }
}
