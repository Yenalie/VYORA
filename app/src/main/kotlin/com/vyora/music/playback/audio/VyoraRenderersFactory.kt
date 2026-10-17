package com.vyora.music.playback.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.vyora.music.data.local.LosslessOutput
import com.vyora.music.data.settings.OutputPcmMode
import com.vyora.music.data.settings.SettingsRepository

/**
 * One player's audio pipeline: Sound Check and spatial audio inside the sink's processor chain, the
 * output-precision wrapper around a float-capable sink, and a codec selector that steps around Samsung's
 * FLAC decoder while float output is selected. Every ExoPlayer that plays music (the main player and the
 * crossfade tail) gets its own instance, so each levels the track it is playing.
 */
@UnstableApi
class VyoraRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    val loudness = LoudnessProcessor()
    val spatial = SpatialAudioProcessor()

    init {
        setEnableAudioFloatOutput(true)
        setMediaCodecSelector { mimeType, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            if (SettingsRepository.current.outputPcmMode != OutputPcmMode.FLOAT_32) all
            else all.filterNot { OutputPolicy.isUnsafeFloatFlacDecoder(it.name) }.ifEmpty { all }
        }
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(loudness, spatial))
            .build()
        return PrecisionAudioSink(sink, ::floatAllowed)
    }

    companion object {
        fun floatAllowed(): Boolean {
            val out = LosslessOutput.state.value
            return OutputPolicy.shouldUseFloat(SettingsRepository.current.outputPcmMode, out.via, out.advertisesFloat)
        }
    }
}
