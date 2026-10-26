package com.example.ptt.alert

import com.example.ptt.crypto.EnvelopePayload

/** A page that reached this phone. [fromName] comes from the verified roster, never from the push. */
data class IncomingPage(
    val pageId: String,
    val level: String,
    val fromUserId: String,
    val fromName: String?,
    /** The envelope's session id, kept so that a page that rang without an `active_page` row can still be answered. */
    val sessionId: String = ""
) {
    val urgent: Boolean get() = level == EnvelopePayload.LEVEL_URGENT
}

/** Everything the alerting logic does to the notification shade. Implemented by [PageNotifier]. */
interface PageNotices {
    /**
     * The incoming-page notification. [alerting] false posts it quietly (acknowledged). [renotify] false makes
     * an update of a notification that is still showing silent; a notification the user already removed is
     * posted anew, and a new post alerts again.
     */
    fun incoming(
        page: IncomingPage,
        alerting: Boolean,
        acknowledgedElsewhere: Boolean = false,
        renotify: Boolean = true
    )

    /** This phone acknowledged the page: quiet, still answerable. */
    fun acknowledged(page: IncomingPage)

    /** Delivery said another device of this user already answered or this one did. */
    fun answered(page: IncomingPage)

    fun cancelled(pageId: String)

    fun answeredElsewhere(pageId: String)

    fun missed(pageId: String, name: String?)

    /** A `page_ack` for a page this phone has no record of. */
    fun acknowledgedUnknown(pageId: String)

    /** Sender side: [name] answered the page this phone sent. */
    fun senderAnswered(pageId: String, name: String?)

    /** Sender side, for the page states that deserve a notification (`failed`, `missed`). */
    fun senderState(pageId: String, state: String, name: String?)

    fun test()

    /** Removes the page's notification (the page was answered on this phone, or this process no longer knows it). */
    fun clear(pageId: String)

    /** Removes ongoing page notifications a dead process left behind, which nothing could act on or swipe away. */
    fun dropLeftovers()
}

/** Starts and stops the foreground [AlertService] that keeps the process alive while a page rings. */
interface AlertServiceControl {
    fun start()

    fun stop()

    /** The page the foreground notice should open changed (null: none). A no-op while the service is not up. */
    fun refresh(top: IncomingPage?) {}
}

/** Sound that plays once and returns when it has finished; cancelling the caller stops it. */
fun interface AlertSound {
    suspend fun playOnce()
}

interface AlertVibrator {
    /** Starts the repeating long pattern. */
    fun start()

    fun stop()

    /** One short pattern with alarm-class attributes, for a normal page the notification itself cannot vibrate. */
    fun pulse()
}

/** Whether the phone is in a mode where a normal page's notification is silent and does not vibrate. */
fun interface RingerState {
    /** True when the ringer is silent or Do Not Disturb is filtering notifications. */
    fun quiet(): Boolean
}

/** The alarm stream volume. */
interface AlarmVolume {
    fun current(): Int

    fun max(): Int

    fun set(volume: Int)

    /**
     * The volume to put back, persisted before the volume is raised so that a crash or force-stop cannot
     * leave the alarm stream at maximum. Null when nothing is raised.
     */
    var pending: Int?

    /** Puts back the volume this run raised (an in-process stop) and forgets it. */
    fun restorePending() {
        pending?.let {
            set(it)
            pending = null
        }
    }

    /**
     * For app and engine start: a volume left by a crashed or force-stopped earlier run. It is put back only
     * if the alarm stream still sits at the maximum we set; if the user changed it since, theirs stays.
     */
    fun restoreLeftover() {
        pending?.let {
            if (current() == max()) set(it)
            pending = null
        }
    }
}

interface AlertWakeLock {
    /** Holds the lock for at most [timeoutMs]; calling again restarts the timeout. */
    fun acquire(timeoutMs: Long)

    fun release()
}

/** The phone hardware the alert engine drives, bundled so tests can fake all of it. */
class AlertDevices(
    val sound: AlertSound,
    val vibrator: AlertVibrator,
    val volume: AlarmVolume,
    val wakeLock: AlertWakeLock,
    val ringer: RingerState
)
