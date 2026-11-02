package com.example.ptt.net

/**
 * One round-trip measurement taken at [atMs] (docs/protocol.md section 13.9): the RTT of a `pong`, or, when
 * [timedOut], a `ping` still unanswered after [RttSamples.TIMEOUT_MS], which counts as one sample above that.
 */
data class RttSample(val atMs: Long, val millis: Long, val timedOut: Boolean)

/**
 * Turns the pings of one socket into RTT samples for the clip-mode thresholds of section 13.9. Pure: every call
 * passes the time, on the clock the ping's `t` was taken from. Each ping gives exactly one sample: its `pong`, or
 * the timeout, after which a late `pong` is ignored. Not thread-safe by itself; [TalkSocket] serializes access.
 */
class RttSamples {
    /** Sent pings by `t`, with whether the timeout already counted them. */
    private val pending = LinkedHashMap<Long, Boolean>()

    fun pingSent(t: Long) {
        pending[t] = false
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
    }

    /** The sample of the `pong` for the ping sent at [t]; null for an unknown ping or one the timeout counted. */
    fun pong(t: Long, now: Long): RttSample? {
        val counted = pending.remove(t) ?: return null
        return if (counted) null else RttSample(now, (now - t).coerceAtLeast(0), timedOut = false)
    }

    /** One sample for every ping unanswered for [TIMEOUT_MS] or more that the timeout has not counted yet. */
    fun expire(now: Long): List<RttSample> {
        val samples = mutableListOf<RttSample>()
        for (entry in pending.entries) {
            if (!entry.value && now - entry.key >= TIMEOUT_MS) {
                entry.setValue(true)
                samples += RttSample(now, now - entry.key, timedOut = true)
            }
        }
        return samples
    }

    companion object {
        const val TIMEOUT_MS = 800L
        private const val MAX_PENDING = 8
    }
}
