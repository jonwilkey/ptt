package com.example.ptt.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.ptt.net.EnrollmentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Non-secret settings in DataStore: where the server is, whether this device is enrolled or has to
 * enroll again, and the alert preferences (placeholders until the alert PR). The device token is
 * not here; see `DeviceTokenStore`.
 */
class SettingsRepository(private val store: DataStore<Preferences>) : EnrollmentState {
    val serverUrlFlow: Flow<String?> = store.data.map { it[SERVER_URL] }
    val enrolled: Flow<Boolean> = store.data.map { it[ENROLLED] ?: false }
    val needsReenrollmentFlow: Flow<Boolean> = store.data.map { it[NEEDS_REENROLLMENT] ?: false }
    val normalAlertVibrate: Flow<Boolean> = store.data.map { it[NORMAL_VIBRATE] ?: true }
    val urgentAlertMaxVolume: Flow<Boolean> = store.data.map { it[URGENT_MAX_VOLUME] ?: true }

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
    suspend fun clearEnrollment() {
        store.edit {
            it.remove(SERVER_URL)
            it[ENROLLED] = false
            it[NEEDS_REENROLLMENT] = false
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
        val NORMAL_VIBRATE = booleanPreferencesKey("alert_normal_vibrate")
        val URGENT_MAX_VOLUME = booleanPreferencesKey("alert_urgent_max_volume")
    }
}
