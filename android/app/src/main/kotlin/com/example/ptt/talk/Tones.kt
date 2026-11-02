package com.example.ptt.talk

/**
 * The cue tones, generated in code (no audio files): sine waves as PCM16 mono at [SAMPLE_RATE], with a linear
 * fade at each end so they do not click on start or stop. Pure Kotlin (no Android types), so testable anywhere.
 */
object Tones {
    const val SAMPLE_RATE = 44_100

    /** The fade in/out, in ms of [durationMs]. */
    const val FADE_MS = 5L

    /** About -6 dBFS: loud enough for a cue, with headroom so it does not clip or startle. */
    const val AMPLITUDE = Short.MAX_VALUE / 2

    private const val MS_PER_S = 1000L

    /** [durationMs] of a [hz] sine. Starts and ends silent (the fade). */
    fun tone(hz: Double, durationMs: Long): ShortArray {
        val n = (durationMs * SAMPLE_RATE / MS_PER_S).toInt()
        return ShortArray(n) { i -> sample(hz, hz, i, n) }
    }

    /** [durationMs] of a sine sliding from [fromHz] to [toHz]. Starts and ends silent (the fade). */
    fun sweep(fromHz: Double, toHz: Double, durationMs: Long): ShortArray {
        val n = (durationMs * SAMPLE_RATE / MS_PER_S).toInt()
        return ShortArray(n) { i -> sample(fromHz, toHz, i, n) }
    }

    /**
     * The [n]th sample of a linear sweep: the phase at sample i is the area under the (linear) frequency curve,
     * `fromHz * t + (toHz - fromHz) * t^2 / (2 * n)` in sample units.
     */
    private fun sample(fromHz: Double, toHz: Double, i: Int, n: Int): Short {
        val t = i.toDouble()
        val phase = 2.0 * Math.PI / SAMPLE_RATE * (fromHz * t + (toHz - fromHz) * t * t / (2.0 * n))
        return (Math.sin(phase) * fade(i, n) * AMPLITUDE).toInt().toShort()
    }

    /** 0 at the first and last samples, 1 in the middle: a linear fade of [FADE_MS] at each end. */
    private fun fade(i: Int, n: Int): Double {
        val f = (FADE_MS * SAMPLE_RATE / MS_PER_S).toInt().coerceAtMost(n / 2).coerceAtLeast(1)
        return when {
            i < f -> i / f.toDouble()
            i >= n - f -> (n - 1 - i) / f.toDouble()
            else -> 1.0
        }
    }
}
