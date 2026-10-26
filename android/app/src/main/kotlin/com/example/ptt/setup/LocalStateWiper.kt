package com.example.ptt.setup

import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.ContactRepository
import com.example.ptt.data.OutboxDao
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.DeviceTokenStore
import kotlinx.coroutines.sync.Mutex

/**
 * Forgets everything this phone knows about a family: settings, device token, roster, contacts,
 * outbox, active page and the device keys. Used before enrolling (protocol section 5: enrolling
 * discards any earlier local roster) and when the device was removed or lost its keys.
 */
// One collaborator per kind of local state it must forget.
@Suppress("LongParameterList")
class LocalStateWiper(
    private val settings: SettingsRepository,
    private val tokens: DeviceTokenStore,
    private val rosters: RosterRepository,
    private val contacts: ContactRepository,
    private val activePage: ActivePageRepository,
    private val outbox: OutboxDao,
    private val keys: DeviceKeyStore
) {
    /**
     * Serialises every transition of local identity state: enrollment (first wipe through storing) and
     * [AppStartup.resolve]. [wipeAll] does not take it itself (the mutex is not reentrant): callers do.
     */
    val lock = Mutex()

    /**
     * Wipes everything. With [keepReenrollFlag] the needs-re-enrollment flag stays set afterwards, so
     * the enrollment screen can still explain why it is showing (a new enrollment clears it).
     */
    suspend fun wipeAll(keepReenrollFlag: Boolean = false) {
        // Settings first, in one edit that also sets the flag when asked: a process death mid-wipe still shows
        // the explanation, and the API layer refuses to send anything as the old device.
        settings.clearEnrollment(keepReenrollFlag)
        tokens.clear()
        rosters.clear()
        contacts.clear()
        activePage.clear()
        outbox.clear()
        keys.wipe()
    }
}
