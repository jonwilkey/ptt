package com.example.ptt.net

/**
 * One round-trip measurement taken at [atMs] (docs/protocol.md section 13.9): the RTT of a `pong`, or, when
 * [timedOut], a `ping` still unanswered after [RttSamples.TIMEOUT_MS], which counts as one sample above that.
 */
data class RttSample(val atMs: Long, val millis: Long, val timedOut: Boolean)

/**
 * The RTT samples of one socket, oldest first, for the clip-mode thresholds of section 13.9. Pure: every call
 * passes the time, on the clock the ping's `t` was taken from. Each ping gives exactly one sample: its `pong`, or
 * the timeout, after which a late `pong` is ignored. Not thread-safe by itself; [TalkSocket] serializes access.
 */
class RttSamples {
    /** Sent pings by `t`, with whether the timeout already counted them. */
    private val pending = LinkedHashMap<Long, Boolean>()
    private val history = ArrayDeque<RttSample>()

    /** The recent samples, oldest first (at most [CAPACITY]). */
    val samples: List<RttSample> get() = history.toList()

    fun pingSent(t: Long) {
        pending[t] = false
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
    }

    fun pong(t: Long, now: Long) {
        val counted = pending.remove(t) ?: return
        if (!counted) add(RttSample(now, (now - t).coerceAtLeast(0), timedOut = false))
    }

    /** Counts every ping unanswered for [TIMEOUT_MS] or more, once. */
    fun expire(now: Long) {
        for (entry in pending.entries) {
            if (!entry.value && now - entry.key >= TIMEOUT_MS) {
                entry.setValue(true)
                add(RttSample(now, now - entry.key, timedOut = true))
            }
        }
    }

    private fun add(sample: RttSample) {
        history.addLast(sample)
        while (history.size > CAPACITY) history.removeFirst()
    }

    companion object {
        const val TIMEOUT_MS = 800L
        const val CAPACITY = 32
        private const val MAX_PENDING = 8
    }
}
