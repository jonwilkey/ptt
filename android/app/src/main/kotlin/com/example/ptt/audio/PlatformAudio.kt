package com.example.ptt.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build

private const val BUFFER_FRAMES = 8
private const val MAX_EMPTY_READS = 5
private const val EMPTY_READ_SLEEP_MS = 4L
private const val BYTES_PER_SAMPLE = 2

/**
 * `AudioRecord` on the VOICE_COMMUNICATION source (platform echo cancellation and noise suppression tuning), 16 kHz
 * mono PCM16, plus the explicit [AcousticEchoCanceler] and [NoiseSuppressor] effects where the device has them.
 * Constructed idle; [start] begins recording. Needs RECORD_AUDIO (the foreground service holds it).
 */
// The foreground service holds RECORD_AUDIO; a revoked permission throws SecurityException, caught in Recorder.
@SuppressLint("MissingPermission")
class AudioRecordSource : PcmSource {
    private val record: AudioRecord
    private val effects = ArrayList<AudioEffect>(2)

    init {
        val min = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val size = maxOf(min, AudioSpec.FRAME_SAMPLES * BYTES_PER_SAMPLE * BUFFER_FRAMES)
        record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioSpec.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) record.release()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        try {
            attachEffects()
        } catch (e: IllegalStateException) {
            close()
            throw e
        } catch (e: IllegalArgumentException) {
            close()
            throw e
        }
    }

    private fun attachEffects() {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.also {
                it.enabled = true
                effects += it
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.also {
                it.enabled = true
                effects += it
            }
        }
    }

    override fun start() = record.startRecording()

    @Suppress("ReturnCount")
    override fun read(buffer: ShortArray): Int {
        var filled = 0
        var empty = 0
        while (filled < buffer.size) {
            val n = record.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
            if (n < 0) return n
            // A blocking read that keeps returning nothing means the device is not delivering: treat as an error.
            if (n == 0 && ++empty > MAX_EMPTY_READS) return -1
            if (n == 0) Thread.sleep(EMPTY_READ_SLEEP_MS)
            filled += n
        }
        return filled
    }

    override fun close() {
        effects.forEach { it.release() }
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } finally {
            record.release()
        }
    }
}

/** `AudioTrack` for voice communication, streaming PCM16 mono. Plays as data is written. */
class AudioTrackSink(sampleRate: Int) : AudioSink {
    private val track: AudioTrack

    init {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val frameBytes = sampleRate * AudioSpec.FRAME_MS / MS_PER_SECOND * BYTES_PER_SAMPLE
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, frameBytes * BUFFER_FRAMES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            // Keep the device buffer (and so the start threshold on API 29/30, where it equals the buffer) to a few
            // frames: a deep buffer would add its whole depth to the latency, on top of the jitter delay.
            track.bufferSizeInFrames = frameBytes / BYTES_PER_SAMPLE * LOW_WATER_FRAMES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                track.startThresholdInFrames = frameBytes / BYTES_PER_SAMPLE
            }
        } catch (e: IllegalArgumentException) {
            track.release()
            throw e
        } catch (e: IllegalStateException) {
            track.release()
            throw e
        }
    }

    override fun start() = track.play()

    override fun stop() = track.stop()

    override fun flush() {
        track.pause()
        track.flush()
    }

    override fun write(pcm: ShortArray) {
        var offset = 0
        while (offset < pcm.size) {
            val n = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            check(n >= 0) { "AudioTrack write failed" }
            offset += n
        }
    }

    override fun close() {
        try {
            track.stop()
        } finally {
            track.release()
        }
    }

    private companion object {
        const val LOW_WATER_FRAMES = 3
        const val MS_PER_SECOND = 1000
    }
}
