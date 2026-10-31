package com.example.ptt.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OpusCsdTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test(timeout = 60_000)
    fun opusHeadIsRfc7845MonoFamilyZero() {
        val expected = "OpusHead".toByteArray(Charsets.US_ASCII) +
            bytes(1, 1, 0x38, 0x01, 0x80, 0x3E, 0, 0, 0, 0, 0)
        assertArrayEquals(expected, OpusCsd.head())
        assertEquals(19, OpusCsd.head().size)
    }

    @Test(timeout = 60_000)
    fun preSkipIsInNanosecondsLittleEndian() {
        // 312 samples at 48 kHz = 6.5 ms = 6_500_000 ns = 0x632EA0
        assertArrayEquals(bytes(0xA0, 0x2E, 0x63, 0, 0, 0, 0, 0), OpusCsd.preSkipNs())
    }

    @Test(timeout = 60_000)
    fun seekPreRollIs80Milliseconds() {
        // 80_000_000 ns = 0x04C4B400
        assertArrayEquals(bytes(0x00, 0xB4, 0xC4, 0x04, 0, 0, 0, 0), OpusCsd.seekPreRollNs())
    }

    @Test(timeout = 60_000)
    fun frameConstantsMatchTheSpec() {
        assertEquals(320, AudioSpec.FRAME_SAMPLES)
        assertEquals(24_000, AudioSpec.BITRATE)
    }
}
