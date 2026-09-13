package com.example.antennaaimer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

class AlignmentTone {

    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val currentOffset = AtomicReference(Float.MAX_VALUE)
    var enabled = true

    private val sampleRate = 44100

    fun start() {
        if (running.get()) return
        running.set(true)

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
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

        audioTrack = track
        track.play()

        // Single dedicated thread for all audio writes
        audioThread = Thread {
            while (running.get()) {
                if (!enabled) {
                    Thread.sleep(100)
                    continue
                }

                val offset = currentOffset.get()
                when {
                    offset < 1f -> playBeep(track, 1200f, 200, 0)
                    offset < 3f -> playBeep(track, 1000f, 80, 80)
                    offset < 10f -> playBeep(track, 800f, 100, 200)
                    offset < 30f -> playBeep(track, 600f, 100, 500)
                    offset < 60f -> playBeep(track, 400f, 100, 1000)
                    else -> Thread.sleep(200)
                }
            }
        }.apply {
            name = "AlignmentTone"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        audioThread?.join(500)
        audioThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    /**
     * Update the current offset. Called from any thread — just sets an atomic value.
     * The audio thread reads it on its own schedule.
     */
    fun updateAlignment(offsetDegrees: Float) {
        currentOffset.set(offsetDegrees)
    }

    private fun playBeep(track: AudioTrack, frequency: Float, durationMs: Int, silenceMs: Int) {
        if (!running.get()) return
        val numSamples = sampleRate * durationMs / 1000
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * frequency * i / sampleRate
            buffer[i] = (sin(angle) * Short.MAX_VALUE * 0.3).toInt().toShort()
        }
        track.write(buffer, 0, buffer.size)

        if (silenceMs > 0 && running.get()) {
            val silenceSamples = sampleRate * silenceMs / 1000
            track.write(ShortArray(silenceSamples), 0, silenceSamples)
        }
    }
}
