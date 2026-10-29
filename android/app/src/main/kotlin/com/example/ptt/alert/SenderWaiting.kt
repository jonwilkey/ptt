package com.example.ptt.alert

import com.example.ptt.crypto.Roster
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.PageRole
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.outbox.Outbox
import com.example.ptt.push.ActivePageRules
import com.example.ptt.push.SessionKeys
import com.example.ptt.setup.RosterSync
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Renders the sender's "Waiting for <name>" notification from the `active_page` row, the single source of
 * truth, rather than from the places that change it (push dispatcher, sender, outbox, End): whichever of them
 * moves the row, the notification follows, and a restarted process shows what the row says.
 *
 * - A sender row that is live (sending, not delivered, delivered, acknowledged) has the notification; any
 *   other row, or none (answered, over, ended by End, replaced by another page), has none.
 * - A page this phone sent to its own user (Test alert) never has one: recognised from the roster, so it holds
 *   after a process death too.
 * - A row that moves to `failed` while observed (the outbox gave up or the server refused: produced here, not by
 *   a push) posts the "Page to X failed" notice, whether or not the talk screen is showing. A `missed` or
 *   `failed` page ended by a push is announced by the push dispatcher, which also deletes the row.
 * - A page the server will never ring (refused for good, `ended` in its 201, or past the send window: ADR 0007)
 *   is marked `failed` here, for the life of the app and not of the engine or a screen, so it also happens in a
 *   process that only runs the outbox worker. The row then moves to `failed` and the notice below follows.
 * - A row still `sending` after the send window can never be sent (ADR 0007): its notification is removed even
 *   if nothing marked the row failed (the process that gave up was not running).
 */
@Suppress("LongParameterList")
class SenderWaiting(
    private val activePage: ActivePageRepository,
    private val rosterSync: RosterSync,
    private val tokens: DeviceTokenStore,
    private val outbox: Outbox,
    private val sessionKeys: SessionKeys,
    private val waiting: WaitingNotices,
    private val notices: PageNotices,
    private val clock: Clock,
    private val scope: CoroutineScope
) {
    private var previous: ActivePageEntity? = null

    /** Starts observing; call once, at app start. */
    fun start() {
        LostPages(outbox, activePage, sessionKeys).start(scope)
        scope.launch {
            activePage.active.collectLatest { row -> render(row) }
        }
    }

    private suspend fun render(row: ActivePageEntity?) {
        val roster = heldRoster()
        val ownUser = tokens.deviceId()?.let { roster?.find(it)?.userId }
        val own = row?.takeIf { it.role == PageRole.SENDER && it.peerUserId != ownUser }
        val live = own?.takeIf { it.state in LIVE }
        val name = own?.let { o -> roster?.devices?.firstOrNull { it.userId == o.peerUserId }?.displayName }
        val before = previous
        previous = own
        if (live == null) {
            waiting.retain(null)
            if (own?.state == STATE_FAILED && before?.pageId == own.pageId && before.state in LIVE) {
                notices.senderState(own.pageId, STATE_FAILED, name)
            }
            return
        }
        waiting.show(live.pageId, name, live.state)
        waiting.retain(live.pageId)
        if (live.state == ActivePageRules.STATE_SENDING) {
            delay(live.createdAt + Outbox.PAGE_SEND_WINDOW_MS - clock.millis())
            waiting.retain(null)
        }
    }

    private suspend fun heldRoster(): Roster? = try {
        rosterSync.heldRoster()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        null
    }

    private companion object {
        const val STATE_FAILED = LostPages.STATE_FAILED
        val LIVE = setOf(ActivePageRules.STATE_SENDING, LostPages.STATE_NOT_DELIVERED, "delivered", "acknowledged")
    }
}
