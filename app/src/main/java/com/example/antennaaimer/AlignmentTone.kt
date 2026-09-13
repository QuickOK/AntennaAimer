package com.example.antennaaimer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sin

class AlignmentTone {

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playThread: Thread? = null
    var enabled = true

    private val sampleRate = 44100

    fun start() {
        if (isPlaying) return
        isPlaying = true

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    fun stop() {
        isPlaying = false
        playThread?.interrupt()
        playThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    /**
     * Play a beep pattern based on how far off-target we are.
     * @param offsetDegrees total angular offset from target in degrees
     */
    fun updateAlignment(offsetDegrees: Float) {
        if (!enabled || !isPlaying) return

        val track = audioTrack ?: return

        // Determine beep parameters based on offset
        when {
            offsetDegrees < 1f -> {
                // Locked on — continuous high tone
                playBeep(track, frequency = 1200f, durationMs = 200, silenceMs = 0)
            }
            offsetDegrees < 3f -> {
                // Very close — fast beeps
                playBeep(track, frequency = 1000f, durationMs = 80, silenceMs = 80)
            }
            offsetDegrees < 10f -> {
                // Close — medium beeps
                playBeep(track, frequency = 800f, durationMs = 100, silenceMs = 200)
            }
            offsetDegrees < 30f -> {
                // Getting warmer — slow beeps
                playBeep(track, frequency = 600f, durationMs = 100, silenceMs = 500)
            }
            offsetDegrees < 60f -> {
                // Far — very slow beeps
                playBeep(track, frequency = 400f, durationMs = 100, silenceMs = 1000)
            }
            else -> {
                // Way off — silence
                writeSilence(track, 200)
            }
        }
    }

    private fun playBeep(track: AudioTrack, frequency: Float, durationMs: Int, silenceMs: Int) {
        val numSamples = (sampleRate * durationMs / 1000)
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * frequency * i / sampleRate
            buffer[i] = (sin(angle) * Short.MAX_VALUE * 0.3).toInt().toShort()
        }
        track.write(buffer, 0, buffer.size)

        if (silenceMs > 0) {
            writeSilence(track, silenceMs)
        }
    }

    private fun writeSilence(track: AudioTrack, durationMs: Int) {
        val numSamples = (sampleRate * durationMs / 1000)
        val silence = ShortArray(numSamples)
        track.write(silence, 0, silence.size)
    }
}
