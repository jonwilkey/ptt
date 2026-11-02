package com.example.ptt.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TonesTest {
    @Test
    fun thePcmLengthIsTheDuration() {
        assertEquals(100L * Tones.SAMPLE_RATE / 1000, Tones.tone(440.0, 100).size.toLong())
        assertEquals(150L * Tones.SAMPLE_RATE / 1000, Tones.sweep(880.0, 440.0, 150).size.toLong())
    }

    @Test
    fun theTonesFadeInAndOut() {
        for (pcm in listOf(Tones.tone(440.0, 100), Tones.sweep(880.0, 440.0, 150))) {
            assertEquals("first sample is silent", 0, pcm.first().toInt())
            assertEquals("last sample is silent", 0, pcm.last().toInt())
            assertTrue("the fade is short", kotlin.math.abs(pcm[1].toInt()) < Short.MAX_VALUE / 10)
            assertTrue("the middle is loud", pcm.maxOf { kotlin.math.abs(it.toInt()) } > Tones.AMPLITUDE * 9 / 10)
            assertTrue("about -6 dBFS", pcm.maxOf { kotlin.math.abs(it.toInt()) } <= Short.MAX_VALUE / 2)
        }
    }

    @Test
    fun everyCueToneIsAtMost150MsAndNotSilent() {
        for (cue in Cue.entries) {
            val pcm = Cues.pcm(cue)
            assertTrue("$cue is at most 150 ms", pcm.size <= 150L * Tones.SAMPLE_RATE / 1000)
            assertTrue("$cue is not silent", pcm.maxOf { kotlin.math.abs(it.toInt()) } > Tones.AMPLITUDE * 9 / 10)
        }
    }
}
