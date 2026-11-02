package com.example.ptt.talk

import com.example.ptt.net.RttSample

/**
 * Whether this phone is in clip mode (docs/protocol.md section 13.9), from the RTT samples and the reconnects of one
 * session. Pure: every input carries its time, on the clock the samples were taken with. Feed it only on protocol 2;
 * protocol 1 never uses clip mode. Not thread-safe: [TalkSessionController] feeds it only from its socket pump and
 * reads the mode elsewhere from the published state.
 *
 * - Entering: [ENTER_SAMPLES] consecutive samples above [ENTER_ABOVE_MS] (a timed-out ping counts as above, whatever
 *   its `millis`), or a reconnect within [ENTER_RECONNECT_WINDOW_MS] of the previous one.
 * - Leaving: [LEAVE_SAMPLES] consecutive samples at or below [LEAVE_AT_MOST_MS], and no reconnect in the last
 *   [LEAVE_QUIET_MS].
 */
class ClipModeDetector {
    var on = false
        private set

    private var above = 0
    private var calm = 0
    private var lastReconnectMs: Long? = null

    fun onSample(sample: RttSample) {
        val high = sample.timedOut || sample.millis > ENTER_ABOVE_MS
        val low = !sample.timedOut && sample.millis <= LEAVE_AT_MOST_MS
        above = if (high) above + 1 else 0
        calm = if (low) calm + 1 else 0
        if (!on && above >= ENTER_SAMPLES) {
            on = true
        } else if (on && calm >= LEAVE_SAMPLES && !reconnectedWithin(sample.atMs, LEAVE_QUIET_MS)) {
            on = false
        }
    }

    /** A socket of the session completed `join` after an earlier one of the same session closed. */
    fun onReconnect(atMs: Long) {
        if (reconnectedWithin(atMs, ENTER_RECONNECT_WINDOW_MS)) on = true
        lastReconnectMs = atMs
    }

    private fun reconnectedWithin(nowMs: Long, windowMs: Long): Boolean =
        lastReconnectMs?.let { nowMs - it <= windowMs } ?: false

    companion object {
        const val ENTER_ABOVE_MS = 800L
        const val ENTER_SAMPLES = 3
        const val ENTER_RECONNECT_WINDOW_MS = 30_000L
        const val LEAVE_AT_MOST_MS = 400L
        const val LEAVE_SAMPLES = 15
        const val LEAVE_QUIET_MS = 60_000L
    }
}
