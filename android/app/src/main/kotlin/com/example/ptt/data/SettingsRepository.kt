package com.example.ptt.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.TrustedAdmin
import com.example.ptt.net.EnrollmentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Who this phone is, as the server told it at enrollment (the device name is the user's choice). */
data class DeviceProfile(val userId: String, val displayName: String, val admin: Boolean, val deviceName: String)

/**
 * What the last successful registration (`PUT /v1/devices/me`, or the enrollment itself) told the
 * server. The FCM token is kept only as a SHA-256 hash: it is just compared, never sent from here.
 */
data class RegistrationValues(
    val appVersion: String,
    val deviceName: String,
    val fcmTokenHash: String?,
    /** The setup checklist as last sent (snake_case key to pass/fail); empty when none was sent. */
    val checklist: Map<String, Boolean> = emptyMap()
)

/**
 * Non-secret settings in DataStore: where the server is, whether this device is enrolled or has to
 * enroll again, and the alert preferences (placeholders until the alert PR). The device token is
 * not here; see `DeviceTokenStore`.
 */
// One small accessor pair per stored setting.
@Suppress("TooManyFunctions")
class SettingsRepository(private val store: DataStore<Preferences>) : EnrollmentState {
    val serverUrlFlow: Flow<String?> = store.data.map { it[SERVER_URL] }
    val enrolled: Flow<Boolean> = store.data.map { it[ENROLLED] ?: false }
    val needsReenrollmentFlow: Flow<Boolean> = store.data.map { it[NEEDS_REENROLLMENT] ?: false }
    val normalAlertVibrate: Flow<Boolean> = store.data.map { it[NORMAL_VIBRATE] ?: true }
    val urgentAlertMaxVolume: Flow<Boolean> = store.data.map { it[URGENT_MAX_VOLUME] ?: true }

    val profileFlow: Flow<DeviceProfile?> = store.data.map { it.toProfile() }

    suspend fun profile(): DeviceProfile? = store.data.first().toProfile()

    suspend fun saveProfile(profile: DeviceProfile) {
        store.edit {
            it[USER_ID] = profile.userId
            it[DISPLAY_NAME] = profile.displayName
            it[IS_ADMIN] = profile.admin
            it[DEVICE_NAME] = profile.deviceName
        }
    }

    /**
     * The admins the first roster must be signed by (the QR's admin list, or this device itself at
     * bootstrap). Ids and public keys only: the enrollment secret and code are never stored.
     */
    suspend fun trustAnchor(): List<TrustedAdmin> {
        val raw = store.data.first()[TRUST_ANCHOR].orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(ANCHOR_SEPARATOR).map { entry ->
            val (id, key) = entry.split(ID_KEY_SEPARATOR)
            TrustedAdmin(id, B64u.decode(key))
        }
    }

    suspend fun setTrustAnchor(admins: List<TrustedAdmin>) {
        val raw = admins.joinToString(ANCHOR_SEPARATOR) { "${it.id}$ID_KEY_SEPARATOR${B64u.encode(it.ed25519)}" }
        store.edit { it[TRUST_ANCHOR] = raw }
    }

    suspend fun clearTrustAnchor() {
        store.edit { it.remove(TRUST_ANCHOR) }
    }

    @Suppress("ReturnCount")
    suspend fun lastRegistration(): RegistrationValues? {
        val prefs = store.data.first()
        val version = prefs[REG_APP_VERSION] ?: return null
        val name = prefs[REG_DEVICE_NAME] ?: return null
        return RegistrationValues(version, name, prefs[REG_FCM_HASH], decodeChecklist(prefs[REG_CHECKLIST]))
    }

    suspend fun setLastRegistration(values: RegistrationValues) {
        store.edit {
            it[REG_APP_VERSION] = values.appVersion
            it[REG_DEVICE_NAME] = values.deviceName
            if (values.fcmTokenHash == null) it.remove(REG_FCM_HASH) else it[REG_FCM_HASH] = values.fcmTokenHash
            it[REG_CHECKLIST] = encodeChecklist(values.checklist)
        }
    }

    /**
     * Ids of devices that were removed from the signed roster but whose `POST /v1/devices/{id}/revoke`
     * has not succeeded yet (docs/protocol.md section 6). Device ids only, nothing secret.
     */
    val pendingRevocationsFlow: Flow<Set<String>> = store.data.map { it[PENDING_REVOCATIONS].orEmpty() }

    suspend fun pendingRevocations(): Set<String> = store.data.first()[PENDING_REVOCATIONS].orEmpty()

    suspend fun addPendingRevocation(deviceId: String) {
        store.edit { it[PENDING_REVOCATIONS] = it[PENDING_REVOCATIONS].orEmpty() + deviceId }
    }

    suspend fun removePendingRevocation(deviceId: String) {
        store.edit { it[PENDING_REVOCATIONS] = it[PENDING_REVOCATIONS].orEmpty() - deviceId }
    }

    private fun encodeChecklist(map: Map<String, Boolean>): String =
        map.toSortedMap().entries.joinToString(ANCHOR_SEPARATOR) { "${it.key}=${if (it.value) "1" else "0"}" }

    private fun decodeChecklist(raw: String?): Map<String, Boolean> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split(ANCHOR_SEPARATOR).associate { entry ->
            val (key, value) = entry.split("=")
            key to (value == "1")
        }
    }

    @Suppress("ReturnCount")
    private fun Preferences.toProfile(): DeviceProfile? {
        val userId = this[USER_ID] ?: return null
        val name = this[DISPLAY_NAME] ?: return null
        val device = this[DEVICE_NAME] ?: return null
        return DeviceProfile(userId, name, this[IS_ADMIN] ?: false, device)
    }

    override suspend fun serverUrl(): String? = store.data.first()[SERVER_URL]

    override suspend fun needsReenrollment(): Boolean = store.data.first()[NEEDS_REENROLLMENT] ?: false

    override fun markNeedsReenrollment() = runBlocking {
        store.edit { it[NEEDS_REENROLLMENT] = true }
        Unit
    }

    /**
     * Records a completed enrollment against [serverUrl] (validated by [ServerUrl.normalize]) and clears
     * the re-enroll flag.
     */
    suspend fun setEnrolled(serverUrl: String) {
        store.edit {
            it[SERVER_URL] = serverUrl
            it[ENROLLED] = true
            it[NEEDS_REENROLLMENT] = false
        }
    }

    /** Back to "not enrolled" (a reset, or before enrolling again). */
    suspend fun clearEnrollment(keepReenrollFlag: Boolean = false) {
        store.edit {
            it.remove(SERVER_URL)
            it[ENROLLED] = false
            it[NEEDS_REENROLLMENT] = keepReenrollFlag
            for (key in listOf(USER_ID, DISPLAY_NAME, DEVICE_NAME, TRUST_ANCHOR, REG_APP_VERSION, REG_DEVICE_NAME)) {
                it.remove(key)
            }
            it.remove(IS_ADMIN)
            it.remove(REG_FCM_HASH)
            it.remove(REG_CHECKLIST)
            it.remove(PENDING_REVOCATIONS)
        }
    }

    suspend fun setNormalAlertVibrate(value: Boolean) {
        store.edit { it[NORMAL_VIBRATE] = value }
    }

    suspend fun setUrgentAlertMaxVolume(value: Boolean) {
        store.edit { it[URGENT_MAX_VOLUME] = value }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val ENROLLED = booleanPreferencesKey("enrolled")
        val NEEDS_REENROLLMENT = booleanPreferencesKey("needs_reenrollment")
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val IS_ADMIN = booleanPreferencesKey("is_admin")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val TRUST_ANCHOR = stringPreferencesKey("trust_anchor")
        val REG_APP_VERSION = stringPreferencesKey("reg_app_version")
        val REG_DEVICE_NAME = stringPreferencesKey("reg_device_name")
        val REG_FCM_HASH = stringPreferencesKey("reg_fcm_hash")
        val REG_CHECKLIST = stringPreferencesKey("reg_checklist")
        val PENDING_REVOCATIONS = stringSetPreferencesKey("pending_revocations")
        const val ANCHOR_SEPARATOR = ","
        const val ID_KEY_SEPARATOR = ":"
        val NORMAL_VIBRATE = booleanPreferencesKey("alert_normal_vibrate")
        val URGENT_MAX_VOLUME = booleanPreferencesKey("alert_urgent_max_volume")
    }
}
