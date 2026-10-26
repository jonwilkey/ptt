package com.example.ptt.alert

import android.util.Log
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.OutboxAction
import com.example.ptt.push.SessionKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The alerting policy (spec "Alert levels" and "Alerting mechanics"), free of Android classes so it is tested
 * with fakes and virtual time. It tracks every page this phone was paged with and drives the phone through
 * [AlertDevices] and the notification shade through [PageNotices].
 *
 * Several pages can be known at once (each has its own notification). The sound, vibration, volume and
 * wake lock follow the highest level among the pages that are still ringing: while any urgent page rings,
 * the urgent tone plays and normal pages stop re-triggering (their notifications stay). The foreground
 * service runs exactly while some page is ringing, i.e. not yet acknowledged.
 *
 * Everything that must be undone when ringing stops (tone, vibration, alarm volume, wake lock) is undone by
 * the one function [recompute], which every stop path (answer, acknowledge, cancel, missed, the service
 * being destroyed) goes through.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class AlertEngine(
    private val scope: CoroutineScope,
    private val devices: AlertDevices,
    private val notices: PageNotices,
    private val service: AlertServiceControl,
    private val outbox: Outbox,
    private val activePage: ActivePageRepository,
    private val sessionKeys: SessionKeys,
    private val maxVolumeEnabled: () -> Boolean = { true }
) {
    private class Alert(val page: IncomingPage, var acknowledged: Boolean) {
        /** Acknowledged by another phone (not by the user here): only changes the notification's wording. */
        var elsewhere = false
        var repeat: Job? = null
        var missed: Job? = null
    }

    private val lock = Any()
    private val alerts = LinkedHashMap<String, Alert>()
    private val missedPages = LinkedHashSet<String>()
    private var serviceRequested = false
    private var wakeLockHeld: String? = null
    private var vibrating = false
    private var toneJob: Job? = null

    /** The page the foreground notice currently opens. */
    private var noticeTop: String? = null

    init {
        // A volume left raised by a crash or force-stop of an earlier run: nothing rings yet, so put it back.
        devices.volume.restoreLeftover()
        // Likewise an ongoing page notification of a dead process: nothing here knows that page.
        notices.dropLeftovers()
    }

    /** True while some page is ringing: the foreground service has a reason to run. */
    val isAlerting: Boolean get() = synchronized(lock) { alerts.values.any { !it.acknowledged } }

    fun knows(pageId: String): Boolean = synchronized(lock) { pageId in alerts }

    /** The caller's name, if this page is known. */
    fun nameOf(pageId: String): String? = pageOf(pageId)?.fromName

    /** What is known of a page this phone was paged with, including its session id, until it ends. */
    fun pageOf(pageId: String): IncomingPage? = synchronized(lock) { alerts[pageId]?.page }

    /** The page the foreground notification should open: the urgent one if any, else the oldest ringing. */
    fun topRinging(): IncomingPage? = synchronized(lock) {
        val ringing = alerts.values.filter { !it.acknowledged }
        (ringing.firstOrNull { it.page.urgent } ?: ringing.firstOrNull())?.page
    }

    /**
     * Starts alerting for [page], or, with [acknowledged] (another phone already acknowledged it), only
     * posts its quiet, answerable notification. A page that is already known is left alone: the server
     * re-pushes a page that was not delivered in time.
     */
    fun start(page: IncomingPage, acknowledged: Boolean = false) = synchronized(lock) {
        if (page.pageId in alerts) return@synchronized
        val alert = Alert(page, acknowledged).also { it.elsewhere = acknowledged }
        alerts[page.pageId] = alert
        when {
            acknowledged -> notices.incoming(page, alerting = false, acknowledgedElsewhere = true)

            page.urgent -> notices.incoming(page, alerting = true)

            else -> {
                trigger(page)
                alert.repeat = scope.launch { retrigger(page) }
            }
        }
        // A newly ringing page restarts the wake lock's timeout, so a second page 10 minutes after the first
        // is not left with only the first one's remaining time.
        if (!acknowledged) wakeLockHeld = null
        // Urgent pages never time out; a normal page is missed after 15 minutes whether acknowledged or not.
        if (!page.urgent) alert.missed = scope.launch { missedAfterTimeout(page.pageId) }
        recompute()
    }

    /** The user pressed Acknowledged: stop ringing, keep the notification, tell the server. Idempotent. */
    suspend fun acknowledge(pageId: String) {
        val page = synchronized(lock) {
            val alert = alerts[pageId]
            if (alert?.acknowledged == true) return
            alert?.also { markAcknowledged(it) }?.page
        }
        // A page this process does not know (a notification outliving a dead process) has nothing to keep.
        if (page == null) notices.clear(pageId) else notices.acknowledged(page)
        // Local state is already right; a failing database or outbox must not undo or hide that.
        guarded("acknowledge") { outbox.enqueue(OutboxAction.Acknowledge(pageId)) }
        guarded("acknowledge state") { activePage.updateState(pageId, STATE_ACKNOWLEDGED) }
    }

    /** The user swiped a page notification away: put it back, or, if the page is unknown here, leave it gone. */
    fun repostAfterSwipe(pageId: String) {
        if (!repost(pageId)) notices.clear(pageId)
    }

    /** Another phone acknowledged it (`page_ack`). Returns false if this phone knows nothing of the page. */
    fun acknowledgedElsewhere(pageId: String): Boolean = synchronized(lock) {
        val alert = alerts[pageId] ?: return@synchronized false
        if (!alert.acknowledged) {
            alert.elsewhere = true
            markAcknowledged(alert)
        }
        notices.incoming(alert.page, alerting = false, acknowledgedElsewhere = true)
        true
    }

    /**
     * Posts the page's notification again as it currently is. For a re-push of a page already held the
     * notification is still showing, so this is a silent update. After a swipe it is a new post, which alerts
     * again, acceptable while the page rings. Returns false if the page is not known.
     */
    fun repost(pageId: String): Boolean = synchronized(lock) {
        val alert = alerts[pageId] ?: return@synchronized false
        if (alert.acknowledged) {
            notices.incoming(alert.page, alerting = false, acknowledgedElsewhere = alert.elsewhere, renotify = false)
        } else {
            notices.incoming(alert.page, alerting = true, renotify = false)
        }
        true
    }

    /** This phone answers: stop everything, remove the notification, tell the server. */
    suspend fun answer(pageId: String) {
        end(pageId)
        notices.clear(pageId)
        guarded("answer") { outbox.enqueue(OutboxAction.Answer(pageId)) }
        guarded("answer state") { activePage.updateState(pageId, STATE_ANSWERED) }
    }

    /** Stops alerting for a page that ended (cancelled, answered elsewhere, ...) and forgets it. */
    fun end(pageId: String): IncomingPage? = synchronized(lock) {
        val alert = alerts.remove(pageId) ?: return@synchronized null
        alert.repeat?.cancel()
        alert.missed?.cancel()
        recompute()
        alert.page
    }

    /**
     * The page was missed (the server's `page_cancel missed`, or 15 minutes without an answer): stop,
     * leave "Missed page from <name>", clear the active page and the session key. Safe to call twice.
     */
    suspend fun miss(pageId: String, fallbackName: String? = null) {
        val page = end(pageId)
        // The local timer and the server's push both arrive; the second must not replace the named
        // notification with a nameless one, so a page already marked missed is left as it is.
        if (markMissed(pageId)) notices.missed(pageId, page?.fromName ?: fallbackName)
        guarded("miss") { activePage.end(pageId) }
        sessionKeys.clear(pageId)
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The type only: never payloads.
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    /** True the first time a page is marked missed. Remembers the last few, which is all a duplicate needs. */
    private fun markMissed(pageId: String): Boolean = synchronized(lock) {
        if (!missedPages.add(pageId)) return@synchronized false
        if (missedPages.size > MISSED_MEMORY) missedPages.remove(missedPages.first())
        true
    }

    /**
     * The service was destroyed. Everything ringing did is undone only if nothing rings now: a late destroy
     * after page A stopped must not silence page B that started meanwhile, which instead gets the service
     * started again.
     */
    fun serviceDestroyed() = synchronized(lock) {
        if (alerts.values.any { !it.acknowledged }) {
            serviceRequested = false
            noticeTop = null
            recompute()
        } else {
            shutdown()
        }
    }

    /** The service is going away: undo everything ringing did, whatever the reason. */
    fun shutdown() = synchronized(lock) {
        serviceRequested = false
        noticeTop = null
        stopUrgentEffects()
        releaseWakeLock()
    }

    private fun markAcknowledged(alert: Alert) {
        alert.acknowledged = true
        alert.repeat?.cancel()
        recompute()
    }

    private suspend fun retrigger(page: IncomingPage) {
        while (true) {
            delay(RETRIGGER_MS)
            // The notification stays up while an urgent page rings, but it must not add its own sound.
            if (!urgentRinging()) trigger(page)
        }
    }

    /**
     * One ring of a normal page: the notification (its channel supplies sound and vibration), plus, when the
     * phone is silent or in Do Not Disturb, which silence the channel, a short vibration of our own ("vibrates
     * only", spec Alert levels).
     */
    private fun trigger(page: IncomingPage) {
        notices.incoming(page, alerting = true)
        if (devices.ringer.quiet()) devices.vibrator.pulse()
    }

    private suspend fun missedAfterTimeout(pageId: String) {
        delay(MISSED_AFTER_MS)
        // This job is cancelled by end(); the rest must still run.
        withContext(NonCancellable) { miss(pageId) }
    }

    private fun urgentRinging(): Boolean =
        synchronized(lock) { alerts.values.any { !it.acknowledged && it.page.urgent } }

    /** Brings service, wake lock, tone, vibration and volume in line with which pages are ringing. */
    private fun recompute() {
        val ringing = alerts.values.filter { !it.acknowledged }
        val urgent = ringing.any { it.page.urgent }
        if (ringing.isNotEmpty() && !serviceRequested) {
            serviceRequested = true
            service.start()
        } else if (ringing.isEmpty() && serviceRequested) {
            serviceRequested = false
            noticeTop = null
            service.stop()
        }
        val top = topRinging()
        if (serviceRequested && top?.pageId != noticeTop) {
            // The foreground notice follows the top page (a later urgent page replaces a normal one).
            noticeTop = top?.pageId
            service.refresh(top)
        }
        when {
            urgent -> holdWakeLock(URGENT, URGENT_WAKE_LOCK_MS)
            ringing.isNotEmpty() -> holdWakeLock(NORMAL, NORMAL_WAKE_LOCK_MS)
            else -> releaseWakeLock()
        }
        if (urgent) startUrgentEffects() else stopUrgentEffects()
    }

    private fun holdWakeLock(level: String, timeoutMs: Long) {
        if (wakeLockHeld == level) return
        wakeLockHeld = level
        devices.wakeLock.acquire(timeoutMs)
    }

    private fun releaseWakeLock() {
        if (wakeLockHeld == null) return
        wakeLockHeld = null
        devices.wakeLock.release()
    }

    private fun startUrgentEffects() {
        if (devices.volume.pending == null && maxVolumeEnabled()) {
            // Persisted before the volume moves, so even a crash cannot lose the original.
            devices.volume.pending = devices.volume.current()
            devices.volume.set(devices.volume.max())
        }
        if (!vibrating) {
            vibrating = true
            devices.vibrator.start()
        }
        if (toneJob == null) {
            toneJob = scope.launch {
                while (true) {
                    renewUrgentWakeLock()
                    devices.sound.playOnce()
                    delay(URGENT_GAP_MS)
                }
            }
        }
    }

    private fun stopUrgentEffects() {
        toneJob?.cancel()
        toneJob = null
        if (vibrating) {
            vibrating = false
            devices.vibrator.stop()
        }
        devices.volume.restorePending()
    }

    /**
     * A timed lock, renewed on every round, so a stuck loop cannot hold the CPU forever. Renewed only while
     * the urgent lock is still the one held, checked under [lock]: a round that was cancelled or overtaken
     * by a level change never takes the lock again.
     */
    private fun renewUrgentWakeLock() = synchronized(lock) {
        if (wakeLockHeld == URGENT) devices.wakeLock.acquire(URGENT_WAKE_LOCK_MS)
    }

    companion object {
        const val RETRIGGER_MS = 30_000L
        const val MISSED_AFTER_MS = 15 * 60 * 1_000L
        const val URGENT_GAP_MS = 3_000L

        /** Renewed every tone round (a few seconds), so one minute only bounds a wedged loop. */
        const val URGENT_WAKE_LOCK_MS = 60_000L

        /** A normal page rings for at most 15 minutes; the lock is not renewed and lapses just after. */
        const val NORMAL_WAKE_LOCK_MS = MISSED_AFTER_MS + 30_000L
        private const val TAG = "AlertEngine"
        private const val MISSED_MEMORY = 32
        private const val URGENT = "urgent"
        private const val NORMAL = "normal"
        private const val STATE_ACKNOWLEDGED = "acknowledged"
        private const val STATE_ANSWERED = "answered"
    }
}
