package com.example.ptt.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val INPUT_WAIT_US = 20_000L
private const val INPUT_ATTEMPTS = 13
private const val FIRST_OUTPUT_WAIT_US = 10_000L
private const val DECODE_WAIT_US = 20_000L
private const val FRAME_US = AudioSpec.FRAME_MS * 1000L
private const val BYTES_PER_SAMPLE = 2

/**
 * The platform Opus encoder (`audio/opus`, API 29+): 16 kHz mono, 24 kbps.
 *
 * Settings: `KEY_BIT_RATE` only. MediaFormat has no Opus DTX or VBR key, and the AOSP `C2OpusEnc` runs libopus with
 * its defaults: VBR on, DTX off, so one packet leaves every 20 ms even in silence (the server's 1 s silence rule needs
 * that). The codec is fed exactly one 20 ms frame per call; its CODEC_CONFIG output (the OpusHead) is dropped. Not
 * verifiable under Robolectric; see the on-device checks in the task report.
 */
class MediaCodecOpusEncoder : OpusEncoder {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var frameIndex = 0L

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioSpec.SAMPLE_RATE, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, AudioSpec.BITRATE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // Whatever went wrong, the native codec must not leak.
            codec.release()
            throw e
        }
    }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        require(pcm.size == AudioSpec.FRAME_SAMPLES) { "exactly one 20 ms frame" }
        feed(pcm)
        return drain()
    }

    @Suppress("ThrowsCount")
    private fun feed(pcm: ShortArray) {
        var offset = 0
        while (offset < pcm.size) {
            val index = codec.awaitInput()
            val buffer = codec.getInputBuffer(index) ?: throw IOException("no encoder input buffer")
            buffer.clear()
            val count = minOf(buffer.capacity() / BYTES_PER_SAMPLE, pcm.size - offset)
            if (count <= 0) throw IOException("encoder input buffer too small")
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, count)
            val last = offset + count == pcm.size
            codec.queueInputBuffer(index, 0, count * BYTES_PER_SAMPLE, frameIndex * FRAME_US, 0)
            if (last) frameIndex++
            offset += count
        }
    }

    private fun drain(): List<ByteArray> {
        val packets = ArrayList<ByteArray>(1)
        var wait = FIRST_OUTPUT_WAIT_US
        while (true) {
            val index = codec.dequeueOutputBuffer(info, wait)
            when {
                index >= 0 -> {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size in 1..AudioSpec.MAX_PACKET) {
                        packets += copy(codec.getOutputBuffer(index), info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    wait = 0
                }

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return packets

                else -> wait = 0 // format or buffers changed
            }
        }
    }

    override fun close() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
            // already failed or stopped
        }
        codec.release()
    }
}

/**
 * The platform Opus decoder. The stream has no Ogg container, so the three codec-specific buffers come from
 * [OpusCsd]. The decoder's output is always 48 kHz mono 16-bit (the rate is re-read from the output format).
 * It has no packet-loss-concealment input (an empty buffer is not a valid access unit), so [Player] conceals
 * gaps itself.
 */
class MediaCodecOpusDecoder : OpusDecoder {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var packetIndex = 0L
    private var sampleRate = AudioSpec.DECODER_RATE

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioSpec.DECODER_RATE, 1)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(OpusCsd.head()))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(OpusCsd.preSkipNs()))
            format.setByteBuffer("csd-2", ByteBuffer.wrap(OpusCsd.seekPreRollNs()))
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // Whatever went wrong, the native codec must not leak.
            codec.release()
            throw e
        }
    }

    override fun decode(opus: ByteArray): DecodedPcm {
        val index = codec.awaitInput()
        val buffer = codec.getInputBuffer(index) ?: throw IOException("no decoder input buffer")
        buffer.clear()
        buffer.put(opus)
        codec.queueInputBuffer(index, 0, opus.size, packetIndex++ * FRAME_US, 0)
        return DecodedPcm(drain(), sampleRate)
    }

    private fun drain(): ShortArray {
        var out = ShortArray(0)
        var wait = DECODE_WAIT_US
        while (true) {
            val index = codec.dequeueOutputBuffer(info, wait)
            when {
                index >= 0 -> {
                    val bytes = codec.getOutputBuffer(index)
                    if (bytes != null && info.size > 0) {
                        bytes.position(info.offset).limit(info.offset + info.size)
                        val shorts = ShortArray(info.size / BYTES_PER_SAMPLE)
                        bytes.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                        out += shorts
                    }
                    codec.releaseOutputBuffer(index, false)
                    wait = 0
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    // Only 16-bit PCM is written to the sink; a float decoder output would be a platform surprise.
                    val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        android.media.AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (encoding != android.media.AudioFormat.ENCODING_PCM_16BIT) throw IOException("unexpected PCM")
                    wait = 0
                }

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return out

                else -> wait = 0
            }
        }
    }

    override fun close() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
            // already failed or stopped
        }
        codec.release()
    }
}

/** An input buffer index, retrying for about 250 ms (the codec may be busy for a moment) before giving up. */
private fun MediaCodec.awaitInput(): Int {
    repeat(INPUT_ATTEMPTS) {
        val index = dequeueInputBuffer(INPUT_WAIT_US)
        if (index >= 0) return index
    }
    throw IOException("codec input stalled")
}

private fun copy(buffer: ByteBuffer?, info: MediaCodec.BufferInfo): ByteArray {
    val bytes = ByteArray(info.size)
    buffer?.position(info.offset)?.limit(info.offset + info.size)
    buffer?.get(bytes)
    return bytes
}
