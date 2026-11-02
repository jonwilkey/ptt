package com.example.ptt.talk

/**
 * The sounds and buzz the talk screen makes for what the session does. Always best-effort, and never takes audio
 * focus (14h).
 */
interface TalkCues {
    fun play(cue: Cue)
}

/** A cue. All tones are at most 150 ms; [vibrates] adds the buzz. */
enum class Cue(val vibrates: Boolean) {
    /** The peer joined the conversation: two rising tones. */
    JOINED(false),

    /** Your floor request was granted: one short high tone (it may leak into your mic). */
    GO(false),

    /** The peer's transmission ended and the floor is open: a falling tone. */
    OVER(false),

    /** You cannot talk (busy, or nobody there to hear it): a short low tone and a vibration. */
    BUSY(true)
}

/** Which tone each cue is: short, generated in code (no audio files), all at most 150 ms. */
object Cues {
    const val JOINED_FIRST_HZ = 660.0
    const val JOINED_FIRST_MS = 70L
    const val JOINED_SECOND_HZ = 880.0
    const val JOINED_SECOND_MS = 80L
    const val GO_HZ = 1320.0
    const val GO_MS = 80L
    const val OVER_FROM_HZ = 880.0
    const val OVER_TO_HZ = 440.0
    const val OVER_MS = 150L
    const val BUSY_HZ = 220.0
    const val BUSY_MS = 120L

    /** The tone of [cue]: PCM16 mono at [Tones.SAMPLE_RATE]. */
    fun pcm(cue: Cue): ShortArray = when (cue) {
        Cue.JOINED ->
            concat(Tones.tone(JOINED_FIRST_HZ, JOINED_FIRST_MS), Tones.tone(JOINED_SECOND_HZ, JOINED_SECOND_MS))

        Cue.GO -> Tones.tone(GO_HZ, GO_MS)

        Cue.OVER -> Tones.sweep(OVER_FROM_HZ, OVER_TO_HZ, OVER_MS)

        Cue.BUSY -> Tones.tone(BUSY_HZ, BUSY_MS)
    }

    private fun concat(first: ShortArray, second: ShortArray): ShortArray {
        val out = ShortArray(first.size + second.size)
        first.copyInto(out)
        second.copyInto(out, first.size)
        return out
    }
}
