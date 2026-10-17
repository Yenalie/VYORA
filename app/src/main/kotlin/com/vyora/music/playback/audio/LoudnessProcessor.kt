package com.vyora.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp

/*
 * Ported from BitChord (https://github.com/kushagrasinghx/BitChord)
 * app/src/main/java/com/music/bitchord/playback/LoudnessProcessor.kt — GPL-3.0.
 */

/**
 * Sound Check: a constant per-track gain from YouTube's own loudness figure, inside the player so it
 * stacks with crossfade and the sleep timer (which drive the player volume) instead of fighting them.
 *
 * The gain glides over ~40 ms rather than jumping when the track changes, and a fast peak limiter
 * holds the result under full scale, so lifting a quiet master by a few dB never clips.
 * Runs on the 16-bit path; hi-res tracks sent to Android as float bypass it (and are not YouTube
 * streams, so they carry no figure anyway).
 */
@UnstableApi
class LoudnessProcessor : BaseAudioProcessor() {
    /** Target linear gain for whatever is playing now. Read on the audio thread; must be cheap. */
    @Volatile
    var targetGain: () -> Float = { 1f }

    private var channels = 0
    private var gain = 1f
    private var reduction = 1f
    private var glide = 1f
    private var release = 1f
    private var snap = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        glide = coefficient(GLIDE_SECONDS, inputAudioFormat.sampleRate)
        release = coefficient(RELEASE_SECONDS, inputAudioFormat.sampleRate)
        snap = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val target = runCatching { targetGain() }.getOrDefault(1f).coerceIn(MIN_GAIN, MAX_GAIN)
        if (snap) {
            gain = target
            snap = false
        }
        if (target == 1f && abs(gain - 1f) < SETTLED && reduction >= PARK) {
            gain = 1f
            reduction = 1f
            out.put(inputBuffer)
            out.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())
        val frames = bytes / (2 * channels)
        val samples = FloatArray(channels)
        var g = gain
        var gr = reduction
        repeat(frames) {
            g += (target - g) * glide
            var peak = 0f
            for (c in 0 until channels) {
                val v = inputBuffer.short / 32768f
                samples[c] = v
                val m = abs(v) * g
                if (m > peak) peak = m
            }
            val wanted = if (peak > LIMIT) LIMIT / peak else 1f
            gr = if (wanted < gr) wanted else gr + (wanted - gr) * release
            val applied = g * gr
            for (c in 0 until channels) out.putShort(toShort(samples[c] * applied))
        }
        // An odd trailing byte (never expected) is copied through rather than dropped.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        gain = g
        reduction = gr
        out.flip()
    }

    override fun onFlush() {
        reduction = 1f
        snap = true
    }

    override fun onReset() {
        channels = 0
        gain = 1f
        reduction = 1f
        snap = true
    }

    /** Called when the player moves to another track so the new gain applies at once, not over a glide. */
    fun onTrackChanged() {
        snap = true
    }

    private fun coefficient(seconds: Double, rate: Int): Float =
        if (rate <= 0) 1f else (1.0 - exp(-1.0 / (seconds * rate))).toFloat()

    companion object {
        private const val LIMIT = 0.985f
        private const val MIN_GAIN = 0.1f
        private const val MAX_GAIN = 1.5f
        private const val GLIDE_SECONDS = 0.04
        private const val RELEASE_SECONDS = 0.08
        private const val SETTLED = 1e-4f
        private const val PARK = 0.97f

        internal fun toShort(v: Float): Short {
            val s = (v * 32767f).let { if (it >= 0f) it + 0.5f else it - 0.5f }.toInt()
            return s.coerceIn(-32768, 32767).toShort()
        }
    }
}
