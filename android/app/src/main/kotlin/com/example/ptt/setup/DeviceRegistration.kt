package com.example.ptt.setup

import com.example.ptt.data.RegistrationValues
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.ApiResult
import com.example.ptt.net.PttApi
import com.example.ptt.net.UpdateDeviceRequest
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the server's record of this device (`PUT /v1/devices/me`) current: app version, device
 * name and FCM token. Only values that changed since the last successful registration (the
 * enrollment request counts as one) are sent. The `checklist` field belongs to a later PR.
 */
class DeviceRegistration(
    private val api: PttApi,
    private val settings: SettingsRepository,
    private val fcm: FcmTokenSource,
    private val appVersion: String
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
        val versionChanged = last?.appVersion != appVersion
        val nameChanged = last?.deviceName != profile.deviceName
        val tokenChanged = token != null && hash != last?.fcmTokenHash
        if (!versionChanged && !nameChanged && !tokenChanged) return@withLock true
        val request = UpdateDeviceRequest(
            fcmToken = if (tokenChanged) token else null,
            appVersion = if (versionChanged) appVersion else null,
            deviceName = if (nameChanged) profile.deviceName else null
        )
        val ok = api.updateDevice(request) is ApiResult.Success
        if (ok) {
            settings.setLastRegistration(
                RegistrationValues(
                    appVersion,
                    profile.deviceName,
                    hash ?: last?.fcmTokenHash
                )
            )
        }
        ok
    }

    /** For `FirebaseMessagingService.onNewToken` (PR 11). */
    suspend fun onNewToken(token: String): Boolean = sync(token)

    companion object {
        /** SHA-256 hex of an FCM token, as stored in [RegistrationValues]. */
        fun hashOf(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
