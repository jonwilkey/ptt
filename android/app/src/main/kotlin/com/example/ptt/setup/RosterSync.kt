package com.example.ptt.setup

import android.util.Log
import com.example.ptt.crypto.DeviceIdentity
import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.DeviceKeysUnavailable
import com.example.ptt.crypto.ProtocolException
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDecision
import com.example.ptt.crypto.RosterDevice
import com.example.ptt.crypto.RosterEditor
import com.example.ptt.crypto.RosterRejection
import com.example.ptt.crypto.RosterVerifier
import com.example.ptt.crypto.SignedObject
import com.example.ptt.crypto.TrustedAdmin
import com.example.ptt.data.ContactRepository
import com.example.ptt.data.RosterRepository
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.ApiResult
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.PttApi
import com.example.ptt.net.RosterResponse
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncResult {
    /** A newer roster was verified and stored. */
    data object Updated : SyncResult

    /** The server's roster is the one already held (or a rejected one was ignored). */
    data object NoChange : SyncResult

    /** The server has no roster yet. */
    data object NoRoster : SyncResult

    /** The server's roster failed verification (the current roster is kept). */
    data class Rejected(val reason: String) : SyncResult

    /** Network failure, 5xx or 429: try again later. */
    data object Unavailable : SyncResult

    /** Not enrolled, or the server answered 401: the start-up routing will decide what happens next. */
    data object NotEnrolled : SyncResult

    /** The device keys or token cannot be used. */
    data object KeysUnavailable : SyncResult

    /** An accepted roster no longer lists this device; it is now marked as needing re-enrollment. */
    data object Removed : SyncResult
}

/** What an admin edit decides for the roster it was shown. */
sealed interface RosterEdit {
    /** Publish [roster] (built from the roster the edit was given, as version+1). */
    class Next(val roster: Roster) : RosterEdit

    /** Do not sign anything; [reason] is a stable tag the caller maps to a message. */
    class Refuse(val reason: String) : RosterEdit
}

sealed interface PublishResult {
    /** The server accepted the roster and it is now stored, exactly as a synced roster would be. */
    class Published(val roster: Roster) : PublishResult

    /** The edit refused to sign. */
    class Refused(val reason: String) : PublishResult

    /** The server says `roster_stale` (after one sync and one re-sign when returned from [RosterSync.publishEdit]). */
    data object Stale : PublishResult

    /** The server rejected the roster (400, or any other final answer). */
    data object ServerRejected : PublishResult

    /** The roster the server accepted failed local verification (not stored). */
    data object LocallyRejected : PublishResult

    /** No roster is held yet, or this device is not an admin in it. */
    data object NotAdmin : PublishResult

    /** Network failure, 5xx or 429: nothing was published (or the outcome is unknown); try again. */
    data object Unavailable : PublishResult

    /** Not enrolled, or a 401: the start-up routing decides what happens next. Nothing is wiped here. */
    data object NotEnrolled : PublishResult

    data object KeysUnavailable : PublishResult
}

/**
 * Fetches `GET /v1/roster`, verifies it with [RosterVerifier] and stores it (docs/protocol.md
 * sections 4 and 5), and, for the bootstrap phone, signs and publishes roster version 1. Safe to
 * call from several places at once: calls are serialised. Logs only reasons, never payloads, keys or names.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class RosterSync(
    private val api: PttApi,
    private val rosters: RosterRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val tokens: DeviceTokenStore,
    private val keyStore: DeviceKeyStore,
    private val clock: Clock,
    private val log: (String) -> Unit = { Log.w(TAG, it) }
) {
    private val lock = Mutex()

    private class Self(val identity: DeviceIdentity, val keys: DeviceKeys)

    suspend fun sync(): SyncResult = lock.withLock {
        val self = self() ?: return@withLock failureFor()
        fetch(self, allowBootstrap = true)
    }

    /** Signs and publishes roster v1 for the bootstrap phone, then stores it. A no-op once a roster is held. */
    suspend fun publishBootstrap(): SyncResult = lock.withLock {
        val self = self() ?: return@withLock failureFor()
        if (isBootstrapPending(self)) publish(self) else SyncResult.NoChange
    }

    /** The verified roster this phone holds, or null. A plain read: it does not wait for a running sync. */
    suspend fun heldRoster(): Roster? = currentRoster()

    /** The held roster, but only if this device is listed in it as an admin (what [publishEdit] requires). */
    suspend fun heldAdminRoster(): Roster? {
        val id = tokens.deviceId() ?: return null
        return currentRoster()?.takeIf { it.find(id)?.admin == true }
    }

    /**
     * Publishes a roster that this admin signs, under the same lock as [sync], so an admin publish never
     * interleaves with a sync or another publish. [edit] sees the roster held at that moment and decides
     * what to sign. The signed roster is `PUT /v1/roster`; only on 204 is it run through
     * [RosterVerifier] against the current roster and stored (the same path as a synced roster).
     *
     * On `409 roster_stale` the newer roster is fetched and verified (still under the lock) and [edit]
     * runs again against it, once; a second `roster_stale` returns [PublishResult.Stale]. Nothing is
     * ever wiped here: a 401 only reports [PublishResult.NotEnrolled].
     */
    suspend fun publishEdit(edit: suspend (Roster) -> RosterEdit): PublishResult = lock.withLock {
        val self = self() ?: return@withLock failurePublish()
        var result = publishOnce(self, edit)
        if (result === PublishResult.Stale) {
            // The phone is behind the server: catch up, then re-evaluate the edit against the new roster.
            result = when (fetch(self, allowBootstrap = false)) {
                SyncResult.Removed, SyncResult.NotEnrolled -> PublishResult.NotEnrolled
                SyncResult.Unavailable -> PublishResult.Unavailable
                SyncResult.KeysUnavailable -> PublishResult.KeysUnavailable
                is SyncResult.Rejected -> PublishResult.LocallyRejected
                else -> publishOnce(self, edit)
            }
        }
        result
    }

    private suspend fun failurePublish(): PublishResult =
        if (tokens.deviceId() == null) PublishResult.NotEnrolled else PublishResult.KeysUnavailable

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun publishOnce(self: Self, edit: suspend (Roster) -> RosterEdit): PublishResult {
        val current = currentRoster()
        if (current == null || current.find(self.identity.deviceId)?.admin != true) return PublishResult.NotAdmin
        val next = when (val decision = edit(current)) {
            is RosterEdit.Refuse -> return PublishResult.Refused(decision.reason)
            is RosterEdit.Next -> decision.roster
        }
        val signed = try {
            RosterEditor.sign(next, self.identity.deviceId, self.keys)
        } catch (_: ProtocolException) {
            log("admin roster could not be signed")
            return PublishResult.Refused(REASON_UNSIGNABLE)
        }
        val wire = signed.toWire()
        return when (val put = api.putRoster(wire)) {
            is ApiResult.Success -> storePublished(self, current, signed, wire)

            is ApiResult.Final -> when {
                put.status == STATUS_CONFLICT && put.code == CODE_ROSTER_STALE -> PublishResult.Stale

                put.isUnauthorized -> PublishResult.NotEnrolled

                else -> {
                    log("admin roster refused: status ${put.status}")
                    PublishResult.ServerRejected
                }
            }

            is ApiResult.Retryable -> PublishResult.Unavailable

            is ApiResult.NotEnrolled -> PublishResult.NotEnrolled
        }
    }

    private suspend fun storePublished(self: Self, current: Roster, signed: SignedObject, wire: String): PublishResult =
        when (val decision = RosterVerifier.accept(current, emptyList(), self.identity, signed)) {
            is RosterDecision.Accepted -> {
                store(self, decision.roster, wire, signed)
                refreshContacts()
                PublishResult.Published(decision.roster)
            }

            is RosterDecision.Rejected -> {
                log("own admin roster rejected: ${decision.reason}")
                PublishResult.LocallyRejected
            }
        }

    private suspend fun failureFor(): SyncResult =
        if (tokens.deviceId() == null) SyncResult.NotEnrolled else SyncResult.KeysUnavailable

    private fun self(): Self? {
        val id = tokens.deviceId() ?: return null
        return try {
            val keys = DeviceKeys.load(keyStore)
            Self(DeviceIdentity(id, keys.ed25519Public, keys.x25519Public), keys)
        } catch (_: DeviceKeysUnavailable) {
            null
        }
    }

    private suspend fun fetch(self: Self, allowBootstrap: Boolean): SyncResult {
        val result = when (val response = api.getRoster()) {
            is ApiResult.Success -> accept(self, response.body)

            is ApiResult.Final -> when {
                response.status == STATUS_NOT_FOUND ->
                    if (allowBootstrap && isBootstrapPending(self)) publish(self) else SyncResult.NoRoster

                response.isUnauthorized -> SyncResult.NotEnrolled

                else -> SyncResult.Unavailable
            }

            is ApiResult.Retryable -> SyncResult.Unavailable

            is ApiResult.NotEnrolled -> SyncResult.NotEnrolled
        }
        if (result is SyncResult.Updated || result == SyncResult.NoChange) refreshContacts()
        return result
    }

    /** True for the bootstrap phone before its roster exists: it trusts only itself. */
    private suspend fun isBootstrapPending(self: Self): Boolean {
        if (rosters.current() != null) return false
        val anchor = settings.trustAnchor()
        return settings.profile()?.admin == true && anchor.size == 1 && anchor[0].id == self.identity.deviceId
    }

    private suspend fun accept(self: Self, response: RosterResponse): SyncResult {
        val candidate = try {
            SignedObject.fromWire(response.rawJson)
        } catch (_: ProtocolException) {
            log("roster rejected: malformed signed object")
            return SyncResult.Rejected("malformed")
        }
        val current = currentRoster()
        val anchor = if (current == null) settings.trustAnchor() else emptyList()
        return when (val decision = RosterVerifier.accept(current, anchor, self.identity, candidate)) {
            is RosterDecision.Accepted -> store(self, decision.roster, response.rawJson, candidate)

            is RosterDecision.Rejected -> {
                // An equal or older version is the normal "nothing new" answer, not an incident.
                if (decision.reason == RosterRejection.STALE_VERSION) {
                    // Equal: silent. Lower: a rollback attempt worth a (reason-only) log line.
                    if (isOlder(candidate, current)) log("roster rejected: ${decision.reason}")
                    SyncResult.NoChange
                } else {
                    log("roster rejected: ${decision.reason}")
                    SyncResult.Rejected(decision.reason.name)
                }
            }
        }
    }

    private fun isOlder(candidate: SignedObject, current: Roster?): Boolean = try {
        current != null && Roster.parse(candidate.payload).version < current.version
    } catch (_: ProtocolException) {
        false
    }

    private suspend fun store(self: Self, roster: Roster, signedJson: String, signed: SignedObject): SyncResult {
        // One row holds the signed object exactly as received and its verified payload: atomic.
        rosters.save(roster.version, signedJson, String(signed.payload, Charsets.UTF_8))
        settings.clearTrustAnchor()
        if (roster.find(self.identity.deviceId) == null) {
            log("roster no longer lists this device")
            settings.markNeedsReenrollment()
            return SyncResult.Removed
        }
        return SyncResult.Updated
    }

    private suspend fun currentRoster(): Roster? {
        val row = rosters.current() ?: return null
        return try {
            Roster.parse(row.parsedJson.toByteArray(Charsets.UTF_8))
        } catch (_: ProtocolException) {
            log("stored roster is unreadable")
            null
        }
    }

    @Suppress("ReturnCount")
    private suspend fun publish(self: Self): SyncResult {
        val profile = settings.profile() ?: return SyncResult.NotEnrolled
        val me = RosterDevice(
            id = self.identity.deviceId,
            userId = profile.userId,
            displayName = profile.displayName,
            deviceName = profile.deviceName,
            admin = true,
            ed25519 = self.identity.ed25519,
            x25519 = self.identity.x25519
        )
        val signed = try {
            RosterEditor.sign(RosterEditor.bootstrap(me, clock.millis()), me.id, self.keys)
        } catch (_: ProtocolException) {
            log("bootstrap roster could not be built")
            return SyncResult.Rejected("bootstrap_invalid")
        }
        val wire = signed.toWire()
        return when (val put = api.putRoster(wire)) {
            is ApiResult.Success -> storeBootstrap(self, signed, wire)

            // A roster already exists on the server: fall back to the normal verified sync.
            is ApiResult.Final -> when {
                put.status == STATUS_CONFLICT -> fetch(self, allowBootstrap = false)

                put.isUnauthorized -> SyncResult.NotEnrolled

                else -> {
                    log("bootstrap roster refused: status ${put.status}")
                    SyncResult.Rejected("bootstrap_refused")
                }
            }

            is ApiResult.Retryable -> SyncResult.Unavailable

            is ApiResult.NotEnrolled -> SyncResult.NotEnrolled
        }
    }

    private suspend fun storeBootstrap(self: Self, signed: SignedObject, wire: String): SyncResult {
        val trusted = listOf(TrustedAdmin(self.identity.deviceId, self.identity.ed25519))
        return when (val decision = RosterVerifier.accept(null, trusted, self.identity, signed)) {
            is RosterDecision.Accepted -> {
                rosters.save(decision.roster.version, wire, String(signed.payload, Charsets.UTF_8))
                settings.clearTrustAnchor()
                refreshContacts()
                SyncResult.Updated
            }

            is RosterDecision.Rejected -> {
                log("own bootstrap roster rejected: ${decision.reason}")
                SyncResult.Rejected(decision.reason.name)
            }
        }
    }

    private suspend fun refreshContacts() {
        if (rosters.current() == null) return
        val result = api.getContacts()
        if (result is ApiResult.Success) contacts.replaceAll(result.body.users)
    }

    private companion object {
        const val TAG = "RosterSync"
        const val STATUS_NOT_FOUND = 404
        const val STATUS_CONFLICT = 409
        const val CODE_ROSTER_STALE = "roster_stale"
        const val REASON_UNSIGNABLE = "unsignable"
    }
}
