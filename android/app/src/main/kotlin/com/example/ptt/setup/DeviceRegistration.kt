package com.example.ptt.setup

import android.util.Log
import com.example.ptt.data.RegistrationValues
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.ApiResult
import com.example.ptt.net.PttApi
import com.example.ptt.net.UpdateDeviceRequest
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the server's record of this device (`PUT /v1/devices/me`) current: app version, device
 * name, FCM token and setup checklist. Only values that changed since the last successful
 * registration (the enrollment request counts as one) are sent.
 */
class DeviceRegistration(
    private val api: PttApi,
    private val settings: SettingsRepository,
    private val fcm: FcmTokenSource,
    private val appVersion: String,
    private val checklist: ChecklistSource = ChecklistSource { emptyMap() }
) {
    private val lock = Mutex()

    /**
     * Registers whatever changed. [knownToken] is a token the caller already has (FCM's `onNewToken`);
     * otherwise the current token is asked for. Returns true when the server is up to date afterwards.
     */
    suspend fun sync(knownToken: String? = null): Boolean = lock.withLock {
        val profile = settings.profile() ?: return@withLock false
        val last = settings.lastRegistration()
        val token = knownToken ?: fcm.token()
        val hash = token?.let { hashOf(it) }
        val currentChecklist = currentChecklist()
        val request = UpdateDeviceRequest(
            fcmToken = token.takeIf { it != null && hash != last?.fcmTokenHash },
            appVersion = appVersion.takeIf { it != last?.appVersion },
            deviceName = profile.deviceName.takeIf { it != last?.deviceName },
            checklist = currentChecklist?.takeIf { it != (last?.checklist ?: emptyMap<String, Boolean>()) }
        )
        if (request.isEmpty()) return@withLock true
        val ok = api.updateDevice(request) is ApiResult.Success
        if (ok) {
            settings.setLastRegistration(
                RegistrationValues(
                    appVersion,
                    profile.deviceName,
                    hash ?: last?.fcmTokenHash,
                    currentChecklist ?: last?.checklist.orEmpty()
                )
            )
        }
        ok
    }

    /** The checklist is best effort: if it cannot be read, nothing is reported and the FCM token still goes out. */
    @Suppress("TooGenericExceptionCaught")
    private fun currentChecklist(): Map<String, Boolean>? = try {
        checklist.serverMap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("DeviceRegistration", "checklist unreadable: ${e.javaClass.simpleName}")
        null
    }

    private fun UpdateDeviceRequest.isEmpty() =
        fcmToken == null && appVersion == null && deviceName == null && checklist == null

    /** For `FirebaseMessagingService.onNewToken` (`push.FcmService`). */
    suspend fun onNewToken(token: String): Boolean = sync(token)

    companion object {
        /** SHA-256 hex of an FCM token, as stored in [RegistrationValues]. */
        fun hashOf(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
