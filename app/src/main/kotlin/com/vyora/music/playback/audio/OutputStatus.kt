package com.vyora.music.playback.audio

import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the player actually negotiated with Android, for the Output Precision readout
 * (BitChord's AudioOutputStatus, trimmed to what VYORA's pipeline can report).
 */
object OutputStatus {
    data class State(
        /** Encoding the AudioTrack was opened with ([C.ENCODING_PCM_16BIT], [C.ENCODING_PCM_FLOAT], …). */
        val sinkEncoding: Int = C.ENCODING_INVALID,
        val sinkSampleRateHz: Int = 0,
        /** What the decoder produced: bit depth of the source as far as the sink sees it. */
        val inputEncoding: Int = C.ENCODING_INVALID,
        val inputSampleRateHz: Int = 0,
        val offload: Boolean = false,
        /** Linear gain Sound Check is applying right now, in dB; `null` while it is not levelling. */
        val loudnessGainDb: Float? = null,
    ) {
        val isFloat get() = sinkEncoding == C.ENCODING_PCM_FLOAT
        val active get() = sinkEncoding != C.ENCODING_INVALID
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun publishSink(encoding: Int, sampleRateHz: Int, offload: Boolean) =
        _state.update { it.copy(sinkEncoding = encoding, sinkSampleRateHz = sampleRateHz, offload = offload) }

    fun publishInput(encoding: Int, sampleRateHz: Int) =
        _state.update { it.copy(inputEncoding = encoding, inputSampleRateHz = sampleRateHz) }

    fun publishLoudness(gainDb: Float?) = _state.update { it.copy(loudnessGainDb = gainDb) }

    fun clearSink() = _state.update { it.copy(sinkEncoding = C.ENCODING_INVALID, sinkSampleRateHz = 0) }

    fun encodingLabel(encoding: Int): String? = when (encoding) {
        C.ENCODING_PCM_16BIT -> "16-bit PCM"
        C.ENCODING_PCM_24BIT -> "24-bit PCM"
        C.ENCODING_PCM_32BIT -> "32-bit PCM"
        C.ENCODING_PCM_FLOAT -> "32-bit float"
        C.ENCODING_PCM_8BIT -> "8-bit PCM"
        else -> null
    }

    fun kiloHertz(hz: Int): String {
        val k = hz / 1000.0
        return if (k == Math.floor(k)) "${k.toInt()} kHz" else "%.1f kHz".format(java.util.Locale.ROOT, k)
    }
}
