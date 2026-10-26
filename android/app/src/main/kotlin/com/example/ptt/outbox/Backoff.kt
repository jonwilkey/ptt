package com.example.ptt.outbox

import kotlin.random.Random

/**
 * Exponential backoff with jitter: the n-th retry waits 1 s, 2 s, 4 s ... up to [capMs] (60 s), each
 * scaled by a random factor in [1 - [jitter], 1] so a delay never exceeds the cap.
 */
class Backoff(
    private val random: Random = Random.Default,
    private val baseMs: Long = BASE_MS,
    private val capMs: Long = CAP_MS,
    private val jitter: Double = JITTER
) {
    /** Delay before the next attempt, after [attempts] failed attempts (at least 1). */
    fun delayMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, MAX_SHIFT)
        val capped = minOf(baseMs shl shift, capMs)
        return (capped * (1.0 - jitter * random.nextDouble())).toLong()
    }

    companion object {
        const val BASE_MS = 1_000L
        const val CAP_MS = 60_000L
        const val JITTER = 0.25
        private const val MAX_SHIFT = 16
    }
}
