package com.example.ptt.talk

import android.util.Log
import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.PageNotices
import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.DeviceKeyStore
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.DeviceKeysUnavailable
import com.example.ptt.crypto.PageEnvelopes
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.SignedObject
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.PageRole
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.SignedObjectDto
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxAction
import com.example.ptt.push.ActivePageRules
import com.example.ptt.push.SelfPages
import com.example.ptt.push.SessionKeys
import com.example.ptt.setup.RosterSync
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SendResult {
    /** The page is stored and queued for sending; the screen may open. */
    data class Sent(val pageId: String) : SendResult

    /**
     * The phone's one page slot is taken by a live page with [peerName] (null if the roster cannot name them).
     * Nothing was changed: ask the user, then send again with `endCurrent = true`.
     */
    data class Busy(val peerName: String?) : SendResult

    /** The verified roster has no phone for that person. */
    data object NoDevices : SendResult

    /** Not enrolled, or no verified roster held yet. */
    data object NotReady : SendResult

    /** The device keys cannot be used; the start-up check (AppStartup) routes the phone back to enrollment. */
    data object KeysUnavailable : SendResult

    data object Error : SendResult
}

/**
 * Sends a page (spec "Sending a page"): wraps a fresh session key for exactly the verified roster's devices of
 * the recipient (never the server's contacts), stores the sender's active-page row, and queues the `POST
 * /v1/pages` in the outbox so it survives offline and process death. A phone has one page or session at a time:
 * a live one is only ended after the caller confirmed.
 */
@Suppress("LongParameterList")
class PageSender(
    private val rosterSync: RosterSync,
    private val tokens: DeviceTokenStore,
    private val keyStore: DeviceKeyStore,
    private val outbox: Outbox,
    private val activePage: ActivePageRepository,
    private val sessionKeys: SessionKeys,
    private val engine: AlertEngine,
    private val notices: PageNotices,
    private val selfPages: SelfPages,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val rules = ActivePageRules(engine, sessionKeys, clock)

    /** One send at a time, so two taps cannot both pass the one-page check. */
    private val lock = Mutex()

    /** A page to this phone's own user (the setup Test alert), or [SendResult.NotReady] if it is not known. */
    suspend fun sendToSelf(level: String): SendResult {
        val self = selfUser() ?: return SendResult.NotReady
        return send(self, level)
    }

    private suspend fun selfUser(): String? {
        val id = tokens.deviceId() ?: return null
        return rosterSync.heldRoster()?.find(id)?.userId
    }

    /**
     * Sends a [level] page to [toUser]. With a live page in the slot and no [endCurrent] nothing changes and
     * the result is [SendResult.Busy]; with it, the current page is ended first (see [endCurrentPage]).
     */
    @Suppress("ReturnCount")
    suspend fun send(toUser: String, level: String, endCurrent: Boolean = false): SendResult = lock.withLock {
        val self = tokens.deviceId() ?: return SendResult.NotReady
        val roster = rosterSync.heldRoster() ?: return SendResult.NotReady
        val selfUser = roster.find(self)?.userId ?: return SendResult.NotReady
        if (roster.devicesOf(toUser).isEmpty()) return SendResult.NoDevices
        val keys = try {
            DeviceKeys.load(keyStore)
        } catch (_: DeviceKeysUnavailable) {
            return SendResult.KeysUnavailable
        } catch (e: IllegalStateException) {
            // A programming error (an unexpected key type), not unavailable keys: nothing is wiped.
            Log.w(TAG, "send failed: ${e.javaClass.simpleName}")
            return SendResult.Error
        }
        try {
            // A page to this phone's own user is received here too, and the receiver keeps the key it unwraps (a
            // key already held would make the dispatcher drop the push as a repeat).
            val sender = Sender(roster, self, keys, keepKey = toUser != selfUser)
            sender.send(toUser, level, endCurrent)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The type only: never payloads.
            Log.w(TAG, "send failed: ${e.javaClass.simpleName}")
            SendResult.Error
        }
    }

    /** One send with what [send] has checked: this device, the roster and the keys. */
    private inner class Sender(val roster: Roster, val self: String, val keys: DeviceKeys, val keepKey: Boolean) {
        suspend fun send(toUser: String, level: String, endCurrent: Boolean): SendResult {
            val pageId = newId()
            val sessionId = newId()
            val live = activePage.current()?.takeUnless { rules.isReplaceable(it, pageId, urgent = false) }
            if (live != null && !endCurrent) return SendResult.Busy(nameOf(roster, live.peerUserId))
            // Built before anything is ended, so a failure here leaves the user's page alone.
            val built = PageEnvelopes.build(roster, self, keys, toUser, pageId, sessionId, level, clock.millis())
            var stored = false
            try {
                if (live != null) endCurrentPage(live)
                if (keepKey) sessionKeys.put(pageId, built.sessionKey) else selfPages.add(pageId)
                store(CreatePageRequest(pageId, toUser, level, sessionId, built.signed.toDto()))
                stored = true
            } finally {
                if (!stored) sessionKeys.clear(pageId)
            }
            return SendResult.Sent(pageId)
        }
    }

    /** The row first, so the screen opened next finds it; then the durable request. */
    private suspend fun store(request: CreatePageRequest) {
        activePage.start(request.id, PageRole.SENDER, request.sessionId, request.to, request.level, STATE_SENDING)
        try {
            outbox.enqueue(OutboxAction.Page(request))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // No request was queued: a row for it would show a page that can never be sent.
            withContext(NonCancellable) { activePage.end(request.id) }
            throw e
        }
    }

    /**
     * Ends the page in the slot so a new one can take it:
     * - a page this phone sent: cancelled (an unsent one never leaves the phone, see ADR 0007);
     * - a page this phone answered: cancelled, as the answerer's End;
     * - a page still ringing or only acknowledged: acknowledged, which stops the ringing on every phone of this
     *   user and tells the sender "Acknowledged". It is not cancelled: the sender's page is theirs to end. Its
     *   notification goes, so it cannot be answered over the new page.
     */
    private suspend fun endCurrentPage(row: ActivePageEntity) {
        when {
            row.role == PageRole.SENDER -> cancel(row.pageId)

            row.state == STATE_ANSWERED -> {
                engine.end(row.pageId)
                cancel(row.pageId)
            }

            else -> {
                engine.acknowledge(row.pageId)
                // Not answerable afterwards: answering later would take the new page's row.
                engine.end(row.pageId)
                notices.clear(row.pageId)
                sessionKeys.clear(row.pageId)
            }
        }
    }

    /** Cancels [pageId] on the server (durably queued), and forgets its row, key and notifications. */
    private suspend fun cancel(pageId: String) {
        outbox.enqueue(OutboxAction.Cancel(pageId))
        notices.clear(pageId)
        sessionKeys.clear(pageId)
        activePage.end(pageId)
    }

    private fun nameOf(roster: Roster, userId: String?): String? =
        roster.devices.firstOrNull { it.userId == userId }?.displayName

    private companion object {
        const val TAG = "PageSender"
        const val STATE_SENDING = ActivePageRules.STATE_SENDING
        const val STATE_ANSWERED = "answered"
    }
}

private fun SignedObject.toDto() = SignedObjectDto(B64u.encode(payload), signer, B64u.encode(sig))
