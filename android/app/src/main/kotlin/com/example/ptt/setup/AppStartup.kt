package com.example.ptt.setup

import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.crypto.DeviceKeysUnavailable
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.DeviceTokenUnavailable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/** Which screen the app shows. */
sealed interface Route {
    /** [explain]: the phone was removed or lost its keys, so it has to be set up again. */
    data class Enroll(val explain: Boolean) : Route

    /** Enrolled, but no roster has been accepted yet. */
    data object Waiting : Route

    data object Home : Route
}

/** UI-free routing at app start and after each sync. Wipes local state when the device cannot continue. */
class AppStartup(
    private val settings: SettingsRepository,
    private val tokens: DeviceTokenStore,
    private val keyStore: DeviceKeyStore,
    private val rosters: RosterRepository,
    private val wiper: LocalStateWiper
) {
    suspend fun resolve(): Route = wiper.lock.withLock { resolveLocked() }

    /**
     * The recovery path for a phone stuck waiting for a roster (for example its admin gave up on the
     * enrollment): wipes all local state under the same lock as [resolve] and routes to a plain
     * enrollment screen. The server-side device record is left for an admin to revoke. If a roster was
     * accepted after the screen showed Waiting, the phone is no longer stuck: nothing is wiped and the
     * normal route is returned.
     */
    suspend fun startOver(): Route = wiper.lock.withLock {
        if (rosters.current() != null) return@withLock resolveLocked()
        wiper.wipeAll()
        Route.Enroll(explain = false)
    }

    @Suppress("ReturnCount")
    private suspend fun resolveLocked(): Route {
        if (settings.needsReenrollment()) {
            wiper.wipeAll(keepReenrollFlag = true)
            return Route.Enroll(explain = true)
        }
        if (!settings.enrolled.first()) return Route.Enroll(explain = false)
        if (!credentialsUsable()) {
            wiper.wipeAll(keepReenrollFlag = true)
            return Route.Enroll(explain = true)
        }
        return if (rosters.current() == null) Route.Waiting else Route.Home
    }

    /**
     * Keystore errors can be transient (boot, an OTA), so each check gets one retry before giving up.
     * Keep this on `keyStore.load()` only: building [com.example.ptt.crypto.DeviceKeys] here could turn a
     * programming error (IllegalStateException) into a wipe of the device's identity.
     */
    private fun credentialsUsable(): Boolean = attempt {
        keyStore.load()
        true
    } && attempt {
        tokens.token() != null
    }

    private fun attempt(check: () -> Boolean): Boolean {
        repeat(2) {
            try {
                return check()
            } catch (_: DeviceKeysUnavailable) {
                // retry
            } catch (_: DeviceTokenUnavailable) {
                // retry
            }
        }
        return false
    }
}
