package com.example.ptt.alert

import kotlinx.coroutines.delay

/** One ordered log of everything the alert engine did to the phone, so tests can assert sequences. */
class DeviceLog {
    val events = mutableListOf<String>()

    fun count(prefix: String) = events.count { it.startsWith(prefix) }

    fun last(prefix: String) = events.last { it.startsWith(prefix) }
}

/** Fake phone hardware. A tone takes [TONE_MS] of (virtual) time to play. */
class FakeDevices(val log: DeviceLog = DeviceLog(), startVolume: Int = 3, val maxVolume: Int = 7) {
    var volume = startVolume
    var wakeLockHeld = false
    var lastWakeLockTimeoutMs = 0L
    var tonePlaying = false
    var acquires = 0

    /** What [RingerState] reports: true for a silent ringer or Do Not Disturb. */
    var quiet = false

    /** The persisted "volume to restore"; it survives across engines in a test, like the real preference. */
    var pendingVolume: Int? = null

    val devices = AlertDevices(
        sound = {
            log.events += "tone"
            tonePlaying = true
            try {
                delay(TONE_MS)
            } finally {
                tonePlaying = false
            }
        },
        vibrator = object : AlertVibrator {
            override fun start() {
                log.events += "vibrate"
            }

            override fun stop() {
                log.events += "vibrate-stop"
            }

            override fun pulse() {
                log.events += "pulse"
            }
        },
        volume = object : AlarmVolume {
            override fun current() = volume

            override fun max() = maxVolume

            override fun set(volume: Int) {
                this@FakeDevices.volume = volume
                log.events += "volume:$volume"
            }

            override var pending: Int?
                get() = pendingVolume
                set(value) {
                    pendingVolume = value
                }
        },
        wakeLock = object : AlertWakeLock {
            override fun acquire(timeoutMs: Long) {
                wakeLockHeld = true
                acquires++
                lastWakeLockTimeoutMs = timeoutMs
            }

            override fun release() {
                wakeLockHeld = false
                log.events += "wakelock-release"
            }
        },
        ringer = { quiet }
    )

    companion object {
        const val TONE_MS = 1_000L
    }
}

class FakeNotices : PageNotices {
    val events = mutableListOf<String>()

    fun count(prefix: String) = events.count { it.startsWith(prefix) }

    override fun incoming(page: IncomingPage, alerting: Boolean, acknowledgedElsewhere: Boolean, renotify: Boolean) {
        events += "incoming:${page.pageId}:${page.level}:alerting=$alerting:elsewhere=$acknowledgedElsewhere" +
            if (renotify) "" else ":quiet-repost"
    }

    override fun acknowledged(page: IncomingPage) {
        events += "acknowledged:${page.pageId}"
    }

    override fun answered(page: IncomingPage) {
        events += "answered:${page.pageId}"
    }

    override fun cancelled(pageId: String) {
        events += "cancelled:$pageId"
    }

    override fun answeredElsewhere(pageId: String) {
        events += "answered-elsewhere:$pageId"
    }

    override fun missed(pageId: String, name: String?) {
        events += "missed:$pageId:$name"
    }

    override fun acknowledgedUnknown(pageId: String) {
        events += "ack-unknown:$pageId"
    }

    override fun senderAnswered(pageId: String, name: String?) {
        events += "sender-answered:$pageId:$name"
    }

    override fun senderState(pageId: String, state: String, name: String?) {
        events += "sender-state:$pageId:$state:$name"
    }

    override fun test() {
        events += "test"
    }

    override fun clear(pageId: String) {
        events += "clear:$pageId"
    }

    var leftoverDrops = 0

    override fun dropLeftovers() {
        leftoverDrops++
    }
}

class FakeServiceControl : AlertServiceControl {
    var starts = 0
    var stops = 0
    val refreshes = mutableListOf<String?>()
    val running get() = starts > stops

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }

    override fun refresh(top: IncomingPage?) {
        refreshes += top?.pageId
    }
}
