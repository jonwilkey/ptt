package com.example.ptt.outbox

import android.util.Log
import com.example.ptt.data.OutboxDao
import com.example.ptt.data.OutboxEntity
import com.example.ptt.data.OutboxKind
import com.example.ptt.net.ApiResult
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.net.CreatePageResponse
import com.example.ptt.net.DeliveredResponse
import com.example.ptt.net.NotEnrolledReason
import com.example.ptt.net.PttApi
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Requests the WorkManager retry path (survives process death). */
fun interface OutboxScheduler {
    fun schedule()
}

/** What one pass over the outbox left behind. */
data class DrainResult(
    /** Earliest time a remaining row is due, or null when the outbox is empty. */
    val nextAttemptAt: Long?,
    /** The device is not enrolled (or must re-enroll): sending stopped, rows are kept. */
    val blocked: Boolean
)

/**
 * Reliable sending (spec "Reliable sending (outbox)"). [enqueue] persists the action, then sends it
 * from an in-process loop with exponential backoff while the process lives; the same rows are the
 * source for the expedited WorkManager job ([OutboxScheduler]) that retries after process death.
 *
 * A row is deleted when the server accepted it (2xx) or answered with a final error (protocol
 * section 6: every non-429 4xx, notably 410 `page_ended` and 409 `already_answered`). Network
 * failures, 5xx and 429 reschedule it (429 and 5xx honour `Retry-After`). Rows of one page are sent
 * in order, so a cancel never overtakes the page it cancels.
 *
 * Rows do not live forever (ADR 0007): a page not accepted within [PAGE_SEND_WINDOW_MS] is dropped
 * (and cancelled on the server if an attempt may have reached it), every other kind after
 * [MAX_ROW_AGE_MS].
 */
@Suppress("LongParameterList", "TooManyFunctions")
class Outbox(
    private val dao: OutboxDao,
    private val api: PttApi,
    private val json: Json,
    private val clock: Clock,
    private val scheduler: OutboxScheduler,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val events = MutableSharedFlow<OutboxOutcome>(extraBufferCapacity = EVENT_BUFFER)

    /** One event per row that left the outbox (sent or rejected). */
    val outcomes: SharedFlow<OutboxOutcome> = events.asSharedFlow()

    private val drainLock = Mutex()
    private val loopLock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loopRunning = false

    /**
     * Persists [action] (idempotent for a page: the page id is the row id), then starts sending.
     * Returns the row id. It never waits for a send in progress, so the action is durable at once.
     */
    suspend fun enqueue(action: OutboxAction): String {
        val id = insert(action, attempts = 0, nextAttemptAt = clock.millis())
        scheduler.schedule()
        kick()
        return id
    }

    /** Call after enrollment completes: schedules the WorkManager job and restarts the in-process loop. */
    fun resume() {
        scheduler.schedule()
        kick()
    }

    /**
     * `delivered` for a pushed page, answered directly because the caller needs the state before
     * alerting (protocol section 9). One attempt; on a network-class failure the action is queued for
     * retry and [DeliveredOutcome.Unreachable] tells the caller to alert anyway.
     */
    suspend fun deliver(pageId: String): DeliveredOutcome = when (val result = api.delivered(pageId)) {
        is ApiResult.Success -> when (result.body.state) {
            STATE_ACKNOWLEDGED -> DeliveredOutcome.Acknowledged

            STATE_ANSWERED -> DeliveredOutcome.Answered

            // "ringing", and any state a newer server adds: alert, which is the safe default.
            else -> DeliveredOutcome.Ringing
        }

        // Only "over, not yours, or not authorised" drops the page. Anything else (an odd status, a 2xx
        // whose body could not be read) must not silence a possibly urgent page: alert.
        is ApiResult.Final -> if (result.status in DROP_STATUSES) {
            DeliveredOutcome.Drop(result.status, result.code)
        } else {
            DeliveredOutcome.Ringing
        }

        is ApiResult.NotEnrolled -> when (result.reason) {
            // A Keystore error can be transient: alert anyway and retry through the outbox.
            NotEnrolledReason.TOKEN_UNAVAILABLE -> queueDeliveredRetry(pageId, null)

            NotEnrolledReason.NOT_ENROLLED,
            NotEnrolledReason.NEEDS_REENROLLMENT -> DeliveredOutcome.Drop(null, result.reason.name.lowercase())
        }

        is ApiResult.Retryable -> queueDeliveredRetry(pageId, result.retryAfterMs)
    }

    private suspend fun queueDeliveredRetry(pageId: String, retryAfterMs: Long?): DeliveredOutcome {
        val wait = maxOf(cappedRetryAfter(retryAfterMs), backoff.delayMs(1))
        insert(OutboxAction.Delivered(pageId), attempts = 1, nextAttemptAt = clock.millis() + wait)
        resume()
        return DeliveredOutcome.Unreachable
    }

    /**
     * Sends every row that is due. Used by the WorkManager job; the in-process loop calls it too. Only
     * one pass runs at a time.
     */
    suspend fun drain(): DrainResult = drainLock.withLock {
        val blockedPages = mutableSetOf<String>()
        val droppedPages = mutableSetOf<String>()
        var blocked = false
        val rows = dao.all()
        val cancelledPages = rows.filter { it.kind == OutboxKind.CANCEL }.map { it.pageId }.toSet()
        for (row in rows) {
            if (blocked) break
            when {
                // The page's PAGE and CANCEL rows were already deleted earlier in this pass.
                row.isDroppedWith(droppedPages) -> Unit

                // Cancelled before any attempt: the page never reached the server, so don't create it.
                row.isUnsentPage() && row.pageId in cancelledPages -> {
                    collapse(row, rows)
                    droppedPages += row.pageId
                }

                isExpired(row) -> {
                    expire(row)
                    if (row.kind == OutboxKind.PAGE) droppedPages += row.pageId
                }

                row.pageId in blockedPages || row.nextAttemptAt > clock.millis() -> blockedPages += row.pageId

                else -> blocked = attempt(row).also { if (it == Step.RETRY) blockedPages += row.pageId } == Step.BLOCKED
            }
        }
        // Only the oldest row of each page can go next, so only those decide when to wake up (see nextWakeUp).
        DrainResult(nextWakeUp(), blocked)
    }

    private suspend fun nextWakeUp(): Long? =
        dao.all().distinctBy { it.pageId }.minOfOrNull { minOf(it.nextAttemptAt, expiresAt(it)) }

    /** Starts the in-process send loop if it is not running (see also [resume]). */
    fun kick() {
        synchronized(loopLock) {
            if (loopRunning) {
                wake.trySend(Unit)
                return
            }
            loopRunning = true
        }
        scope.launch {
            var stoppedCleanly = false
            try {
                runLoop()
                stoppedCleanly = true
            } finally {
                // Only a cancelled loop gets here without having released the flag itself (tryStop).
                if (!stoppedCleanly) synchronized(loopLock) { loopRunning = false }
            }
        }
    }

    private suspend fun runLoop() {
        var failures = 0
        do {
            val result = safeDrain()
            failures = if (result == null) failures + 1 else 0
        } while (waitForNextPass(result, failures))
    }

    /** Sleeps until the next pass is due (or a new row wakes the loop). Returns false when the loop has stopped. */
    private suspend fun waitForNextPass(result: DrainResult?, failures: Int): Boolean {
        val next = result?.nextAttemptAt
        val delayMs = when {
            result == null -> backoff.delayMs(failures)
            result.blocked || next == null -> null
            else -> next - clock.millis()
        }
        if (delayMs == null) return !tryStop()
        if (delayMs > 0) withTimeoutOrNull(delayMs) { wake.receive() }
        return true
    }

    /** One pass, or null if it failed unexpectedly (e.g. a database error): log the type only, never payloads. */
    private suspend fun safeDrain(): DrainResult? = try {
        drain()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "outbox pass failed: ${e.javaClass.simpleName}")
        null
    }

    /** Stops the loop unless a row was enqueued since the last pass. */
    private fun tryStop(): Boolean = synchronized(loopLock) {
        if (wake.tryReceive().isSuccess) return false
        loopRunning = false
        true
    }

    private enum class Step { DONE, RETRY, BLOCKED }

    private suspend fun attempt(row: OutboxEntity): Step {
        // Record the attempt BEFORE sending: if the process dies mid-request the server may have the
        // request, and `attempts == 0` must keep meaning "never sent" (ADR 0007).
        val attempts = row.attempts + 1
        dao.reschedule(row.id, attempts, clock.millis() + backoff.delayMs(attempts))
        val result = try {
            send(row)
        } catch (_: SerializationException) {
            // A row this build cannot read can never be sent: drop it rather than retry forever.
            ApiResult.Final(0, "invalid_row")
        }
        return when (result) {
            is ApiResult.Success -> finish(row, OutboxOutcome.Result.Sent(pageState(result.body)))

            // A 2xx whose body could not be read was still accepted by the server.
            is ApiResult.Final -> finish(
                row,
                if (result.status in HTTP_SUCCESS) {
                    OutboxOutcome.Result.Sent(null)
                } else {
                    OutboxOutcome.Result.Rejected(result.status, result.code)
                }
            )

            is ApiResult.Retryable -> retryLater(row, attempts, result.retryAfterMs)

            is ApiResult.NotEnrolled -> when (result.reason) {
                // Possibly transient; nothing would restart a blocked loop, so back off like a network error.
                NotEnrolledReason.TOKEN_UNAVAILABLE -> retryLater(row, attempts, null)

                // Nothing was sent: undo the recorded attempt.
                NotEnrolledReason.NOT_ENROLLED,
                NotEnrolledReason.NEEDS_REENROLLMENT -> {
                    dao.reschedule(row.id, row.attempts, row.nextAttemptAt)
                    Step.BLOCKED
                }
            }
        }
    }

    private suspend fun retryLater(row: OutboxEntity, attempts: Int, retryAfterMs: Long?): Step {
        val wait = maxOf(cappedRetryAfter(retryAfterMs), backoff.delayMs(attempts))
        dao.reschedule(row.id, attempts, clock.millis() + wait)
        return Step.RETRY
    }

    private fun cappedRetryAfter(retryAfterMs: Long?): Long = (retryAfterMs ?: 0L).coerceIn(0L, RETRY_AFTER_CAP_MS)

    private fun OutboxEntity.isDroppedWith(pages: Set<String>) = pageId in pages && kind in PAGE_AND_CANCEL

    private fun OutboxEntity.isUnsentPage() = kind == OutboxKind.PAGE && attempts == 0

    private fun expiresAt(row: OutboxEntity): Long =
        row.createdAt + if (row.kind == OutboxKind.PAGE) PAGE_SEND_WINDOW_MS else MAX_ROW_AGE_MS

    private fun isExpired(row: OutboxEntity): Boolean = clock.millis() >= expiresAt(row)

    /**
     * Drops an expired row. An expired page that may have reached the server (attempts > 0) is also
     * cancelled there, so it cannot ring after the sender gave up. Only the page's PAGE and CANCEL rows
     * go with it: this phone's own receiver rows (`delivered`, `acknowledge`, `answer`, as in a Test
     * alert to itself) keep their own cap.
     */
    private suspend fun expire(row: OutboxEntity) {
        if (row.kind == OutboxKind.PAGE) {
            dao.all().filter { it.pageId == row.pageId && it.kind in PAGE_AND_CANCEL }.forEach { dao.delete(it.id) }
            if (row.attempts > 0) insert(OutboxAction.Cancel(row.pageId), attempts = 0, nextAttemptAt = clock.millis())
        } else {
            dao.delete(row.id)
        }
        events.tryEmit(OutboxOutcome(row.id, row.kind, row.pageId, OutboxOutcome.Result.Expired))
    }

    /** Deletes an unsent page and the cancels waiting for it, from this pass's snapshot [rows]. */
    private suspend fun collapse(page: OutboxEntity, rows: List<OutboxEntity>) {
        dao.delete(page.id)
        rows.filter { it.kind == OutboxKind.CANCEL && it.pageId == page.pageId }.forEach { dao.delete(it.id) }
        events.tryEmit(OutboxOutcome(page.id, page.kind, page.pageId, OutboxOutcome.Result.CancelledLocally))
    }

    private suspend fun finish(row: OutboxEntity, result: OutboxOutcome.Result): Step {
        dao.delete(row.id)
        events.tryEmit(OutboxOutcome(row.id, row.kind, row.pageId, result))
        return Step.DONE
    }

    private suspend fun send(row: OutboxEntity): ApiResult<Any> = when (row.kind) {
        OutboxKind.PAGE -> api.createPage(json.decodeFromString(CreatePageRequest.serializer(), row.body))
        OutboxKind.DELIVERED -> api.delivered(row.pageId)
        OutboxKind.ACKNOWLEDGE -> api.acknowledge(row.pageId)
        OutboxKind.ANSWER -> api.answer(row.pageId)
        OutboxKind.CANCEL -> api.cancel(row.pageId)
    }

    private fun pageState(body: Any): String? = when (body) {
        is CreatePageResponse -> body.state
        is DeliveredResponse -> body.state
        else -> null
    }

    private suspend fun insert(action: OutboxAction, attempts: Int, nextAttemptAt: Long): String {
        val (id, body) = when (action) {
            is OutboxAction.Page ->
                action.request.id to json.encodeToString(CreatePageRequest.serializer(), action.request)

            else -> newId() to ""
        }
        dao.insert(OutboxEntity(id, action.kind, action.pageId, body, attempts, nextAttemptAt, clock.millis()))
        return id
    }

    companion object {
        /** A page not accepted by the server this long after it was created is abandoned (ADR 0007). */
        const val PAGE_SEND_WINDOW_MS = 15 * 60 * 1_000L

        /** Every other row is dropped after this long, which bounds the table (ADR 0007). */
        const val MAX_ROW_AGE_MS = 24 * 60 * 60 * 1_000L

        /** The outbox never waits longer than this on a `Retry-After`, so a page's rows cannot stall for an hour. */
        const val RETRY_AFTER_CAP_MS = 300_000L
        private const val TAG = "Outbox"
        private val DROP_STATUSES = setOf(401, 403, 404, 410)
        private val PAGE_AND_CANCEL = setOf(OutboxKind.PAGE, OutboxKind.CANCEL)
        private val HTTP_SUCCESS = 200..299
        private const val EVENT_BUFFER = 64
        private const val STATE_ACKNOWLEDGED = "acknowledged"
        private const val STATE_ANSWERED = "answered"
    }
}
