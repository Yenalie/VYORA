package com.vyora.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/*
 * Output precision, after BitChord's PrecisionAudioSink / OutputNegotiator
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0), reduced to the decision VYORA needs.
 */

/**
 * Wraps a float-capable [androidx.media3.exoplayer.audio.DefaultAudioSink] and decides, every time a
 * track's format is configured, whether hi-res PCM (24-bit, 32-bit or float) may go out as 32-bit float.
 *
 * When it may not — 16-bit was chosen, or [OutputPolicy] says the route is unsafe — the samples are
 * reduced to 16-bit here, with TPDF dither, before the wrapped sink sees them. That way the decision
 * follows the route per track (plug in a DAC and the next song goes out as float) without rebuilding
 * the player, and the 16-bit path keeps Sound Check, spatial audio and skip-silence working.
 */
@UnstableApi
class PrecisionAudioSink(
    sink: AudioSink,
    private val floatAllowed: () -> Boolean,
) : ForwardingAudioSink(sink) {
    private var convertFrom = C.ENCODING_INVALID
    private var pendingSource: ByteBuffer? = null
    private var pending: ByteBuffer? = null
    private var scratch: ByteBuffer? = null
    private val random = Random(0x5EED)

    /**
     * Decoders ask the sink whether float is welcome before choosing their output. Saying no while float
     * is not allowed keeps them on 16-bit, which is also what keeps the in-player effects running.
     */
    override fun getFormatSupport(format: Format): Int {
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding == C.ENCODING_PCM_FLOAT &&
            !runCatching(floatAllowed).getOrDefault(false)
        ) return AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val format = config.format
        val hiRes = format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding in CONVERTIBLE
        val useFloat = hiRes && runCatching(floatAllowed).getOrDefault(false)
        convertFrom = if (hiRes && !useFloat) format.pcmEncoding else C.ENCODING_INVALID
        clearPending()
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) {
            OutputStatus.publishInput(format.pcmEncoding, format.sampleRate)
        }
        if (convertFrom == C.ENCODING_INVALID) {
            super.configure(config)
            return
        }
        val reduced = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        super.configure(copyWith(config, reduced))
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (convertFrom == C.ENCODING_INVALID) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val converted = pending?.takeIf { pendingSource === buffer } ?: convert(buffer).also {
            pending = it
            pendingSource = buffer
        }
        val done = super.handleBuffer(converted, presentationTimeUs, encodedAccessUnitCount)
        if (done) {
            buffer.position(buffer.limit())
            clearPending()
        }
        return done
    }

    override fun flush() {
        clearPending()
        super.flush()
    }

    override fun reset() {
        clearPending()
        convertFrom = C.ENCODING_INVALID
        super.reset()
    }

    private fun clearPending() {
        pending = null
        pendingSource = null
    }

    private fun convert(source: ByteBuffer): ByteBuffer {
        val input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (convertFrom) {
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val samples = input.remaining() / bytesPerSample
        val needed = samples * 2
        val out = scratch?.takeIf { it.capacity() >= needed }
            ?: ByteBuffer.allocateDirect(maxOf(needed, 8192)).order(ByteOrder.nativeOrder()).also { scratch = it }
        out.clear()
        repeat(samples) {
            val v = when (convertFrom) {
                C.ENCODING_PCM_24BIT -> {
                    val b0 = input.get().toInt() and 0xFF
                    val b1 = input.get().toInt() and 0xFF
                    val b2 = input.get().toInt()
                    ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
                }
                C.ENCODING_PCM_32BIT -> input.int / 2_147_483_648f
                else -> input.float
            }
            out.putShort(dither16(v))
        }
        out.flip()
        return out
    }

    /** Triangular (TPDF) dither of ±1 LSB, so the reduction to 16 bits leaves noise rather than distortion. */
    private fun dither16(v: Float): Short {
        val tpdf = random.nextFloat() - random.nextFloat()
        val s = Math.round(v.coerceIn(-1f, 1f) * 32767f + tpdf)
        return s.coerceIn(-32768, 32767).toShort()
    }

    companion object {
        val CONVERTIBLE = setOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)

        private fun copyWith(config: AudioSink.AudioSinkConfig, format: Format): AudioSink.AudioSinkConfig {
            val b = AudioSink.AudioSinkConfig.Builder(format)
                .setPreferredBufferSizeOverride(config.preferredBufferSizeOverride)
            config.outputChannelMapping?.let { b.setOutputChannelMapping(it) }
            config.timeline?.let { b.setTimeline(it) }
            config.mediaPeriodId?.let { b.setMediaPeriodId(it) }
            return b.build()
        }
    }
}
