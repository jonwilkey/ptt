package com.example.ptt.setup

import android.util.Log
import com.example.ptt.crypto.ProtocolException
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterEditor
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.ApiResult
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.PttApi
import java.time.Clock

enum class RevokeRefusal {
    /** The phone's lock screen is showing. */
    LOCKED,

    /** This phone cannot remove itself. */
    SELF,

    /** It would leave the family without an admin device. */
    LAST_ADMIN,
    UNKNOWN_DEVICE,
    NOT_ADMIN
}

sealed interface RevokeResult {
    /** Removed from the signed roster and revoked on the server. */
    data object Done : RevokeResult

    /** Removed from the roster, but the server's revoke has not succeeded yet: it keeps being retried. */
    data object RevocationPending : RevokeResult

    /**
     * Removed from the roster, but the server refused (403) to revoke the device's token, so it stays live
     * until an admin revokes it by hand. The pending entry is dropped (it could never succeed).
     */
    data object ServerRefusedRevoke : RevokeResult

    class Refused(val reason: RevokeRefusal) : RevokeResult

    /** Nothing was published (network, a stale or rejected roster, ...); the roster is unchanged. */
    class Failed(val result: PublishResult) : RevokeResult
}

/**
 * Removing a device (docs/protocol.md section 6): publish a signed roster without it, and only then
 * `POST /v1/devices/{id}/revoke`. A removal whose revoke has not succeeded is remembered (device id only)
 * until it does, because the removed device is no longer in the roster for the UI to list.
 */
@Suppress("LongParameterList")
class DeviceRevoker(
    private val api: PttApi,
    private val rosterSync: RosterSync,
    private val settings: SettingsRepository,
    private val tokens: DeviceTokenStore,
    private val lockState: DeviceLockState,
    private val clock: Clock,
    private val log: (String) -> Unit = { Log.w("DeviceRevoker", it) }
) {
    /** Refusals happen before any network call. */
    @Suppress("ReturnCount")
    suspend fun remove(deviceId: String): RevokeResult {
        if (lockState.isLocked()) return RevokeResult.Refused(RevokeRefusal.LOCKED)
        val selfId = tokens.deviceId() ?: return RevokeResult.Refused(RevokeRefusal.NOT_ADMIN)
        var refusal: RevokeRefusal? = null
        val published = rosterSync.publishEdit { current ->
            refusal = null
            when (val plan = plan(current, selfId, deviceId, clock.millis())) {
                is Plan.Refuse -> {
                    refusal = plan.reason
                    RosterEdit.Refuse(plan.reason.name)
                }

                is Plan.Publish -> {
                    // Written before the PUT so a crash right after the server accepts it cannot lose the
                    // revocation. Until the roster really lacks the device, retries ignore the entry.
                    settings.addPendingRevocation(deviceId)
                    RosterEdit.Next(plan.roster)
                }
            }
        }
        return when (published) {
            is PublishResult.Published -> revokeNow(deviceId)

            is PublishResult.Refused -> {
                // A refusal on the roster (UNKNOWN_DEVICE) means the device is already out of it, so an
                // entry (possibly from an earlier attempt) is real and stays. Any other refusal, or a
                // failed signing, published nothing: drop the entry written for this attempt.
                if (refusal != RevokeRefusal.UNKNOWN_DEVICE) settings.removePendingRevocation(deviceId)
                RevokeResult.Refused(refusal ?: RevokeRefusal.NOT_ADMIN)
            }

            PublishResult.NotAdmin -> {
                // A stale retry may have found this phone no longer an admin after the first attempt wrote an entry.
                settings.removePendingRevocation(deviceId)
                RevokeResult.Refused(RevokeRefusal.NOT_ADMIN)
            }

            // The PUT may have reached the server (a lost answer, or a 204 that failed local verification):
            // keep the entry. It is only acted on once the roster really lacks the device.
            PublishResult.Unavailable, PublishResult.LocallyRejected -> RevokeResult.Failed(published)

            // Stale, refused by the server, not enrolled, no keys: definitely nothing was published.
            else -> {
                settings.removePendingRevocation(deviceId)
                RevokeResult.Failed(published)
            }
        }
    }

    /**
     * Remembered revokes whose device is already out of the roster: the ones that must still reach the
     * server. (An entry whose device is still listed means its roster PUT did not go through; it is not
     * acted on, because the roster must be published first.)
     */
    suspend fun outstanding(): Set<String> {
        // A phone that is not an admin can never revoke, so there is nothing to retry from here.
        val roster = rosterSync.heldAdminRoster() ?: return emptySet()
        return settings.pendingRevocations().filter { roster.find(it) == null }.toSet()
    }

    /**
     * Retries every outstanding revoke. Safe to call often (revoke is idempotent). Returns the ids that
     * are still outstanding afterwards.
     */
    suspend fun retryPending(): Set<String> {
        for (id in outstanding()) revokeNow(id)
        return outstanding()
    }

    private suspend fun revokeNow(deviceId: String): RevokeResult {
        var refused = false
        val done = when (val r = api.revokeDevice(deviceId)) {
            is ApiResult.Success -> true

            // 404: the server does not know the device, so there is nothing left to revoke.
            // 403: this phone is not an admin on the server and never can revoke: drop the entry (logged) so
            // it is not retried forever. Any other final error keeps the entry and stays visibly pending.
            is ApiResult.Final -> when (r.status) {
                STATUS_NOT_FOUND -> true

                STATUS_FORBIDDEN -> {
                    log("revoke refused with 403; dropping the pending revocation")
                    refused = true
                    true
                }

                else -> false
            }

            else -> false
        }
        if (!done) return RevokeResult.RevocationPending
        settings.removePendingRevocation(deviceId)
        return if (refused) RevokeResult.ServerRefusedRevoke else RevokeResult.Done
    }

    sealed interface Plan {
        class Publish(val roster: Roster) : Plan

        class Refuse(val reason: RevokeRefusal) : Plan
    }

    companion object {
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_FORBIDDEN = 403

        /** Decides, from the roster alone, whether [deviceId] may be removed by [selfId]. */
        fun plan(current: Roster, selfId: String, deviceId: String, issuedAt: Long): Plan = when {
            deviceId == selfId -> Plan.Refuse(RevokeRefusal.SELF)

            current.find(deviceId) == null -> Plan.Refuse(RevokeRefusal.UNKNOWN_DEVICE)

            current.devices.none { it.admin && it.id != deviceId } -> Plan.Refuse(RevokeRefusal.LAST_ADMIN)

            current.find(selfId)?.admin != true -> Plan.Refuse(RevokeRefusal.NOT_ADMIN)

            else -> try {
                Plan.Publish(RosterEditor.removeDevice(current, deviceId, issuedAt))
            } catch (_: ProtocolException) {
                Plan.Refuse(RevokeRefusal.LAST_ADMIN)
            }
        }
    }
}
