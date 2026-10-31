package com.example.ptt.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The fixed audio parameters of live talk (spec, "Talk and audio"). */
object AudioSpec {
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    const val BITRATE = 24_000

    /** The largest legal Opus packet (docs/protocol.md section 12). */
    const val MAX_PACKET = 1275

    /** The platform Opus decoder always produces 48 kHz (AOSP C2OpusDec); re-read from its output format. */
    const val DECODER_RATE = 48_000
}

/** One 20 ms Opus encoder stream. Created per transmission (a fresh stream pairs with a fresh decoder). */
interface OpusEncoder : AutoCloseable {
    /**
     * Feeds exactly [AudioSpec.FRAME_SAMPLES] samples and returns the packets that are ready: normally one, but the
     * platform codec is asynchronous and may return none (lag) or two (catching up). Each packet is 1..1275 bytes.
     */
    fun encode(pcm: ShortArray): List<ByteArray>
}

/** One decoder stream (fresh per transmission). */
interface OpusDecoder : AutoCloseable {
    /** Decodes one packet to PCM at [DecodedPcm.sampleRate]; the result may be empty (the stream's pre-skip). */
    fun decode(opus: ByteArray): DecodedPcm
}

class DecodedPcm(val samples: ShortArray, val sampleRate: Int)

/**
 * The codec-specific data the platform Opus decoder needs, built locally because the stream carries no Ogg headers.
 * Layout follows AOSP (`OggExtractor`, `C2OpusDec`): csd-0 is the RFC 7845 `OpusHead`, csd-1 the pre-skip and csd-2
 * the seek pre-roll, both as 64-bit nanosecond counts in native (little-endian on every Android ABI) byte order.
 */
object OpusCsd {
    /** libopus's lookahead at 48 kHz (312 samples = 6.5 ms), the pre-skip the platform encoder advertises. */
    const val PRE_SKIP_SAMPLES = 312
    const val SEEK_PRE_ROLL_NS = 80_000_000L
    private const val HEAD_SIZE = 19
    private const val NS_PER_SECOND = 1_000_000_000L

    /** RFC 7845 section 5.1 identification header: mono, no gain, channel mapping family 0. */
    fun head(inputSampleRate: Int = AudioSpec.SAMPLE_RATE, preSkip: Int = PRE_SKIP_SAMPLES): ByteArray =
        ByteBuffer.allocate(HEAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1) // version
            .put(1) // channels
            .putShort(preSkip.toShort())
            .putInt(inputSampleRate)
            .putShort(0) // output gain
            .put(0) // mapping family
            .array()

    fun preSkipNs(preSkip: Int = PRE_SKIP_SAMPLES): ByteArray =
        nanos(preSkip.toLong() * NS_PER_SECOND / AudioSpec.DECODER_RATE)

    fun seekPreRollNs(): ByteArray = nanos(SEEK_PRE_ROLL_NS)

    private fun nanos(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .putLong(value).array()
}
