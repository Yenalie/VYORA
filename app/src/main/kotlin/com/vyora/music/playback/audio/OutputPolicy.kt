package com.vyora.music.playback.audio

import com.vyora.music.data.local.LosslessOutput
import com.vyora.music.data.settings.OutputPcmMode

/*
 * Ported from BitChord (https://github.com/kushagrasinghx/BitChord)
 * app/src/main/java/com/music/bitchord/playback/AudioOutputPolicy.kt — GPL-3.0.
 */

/**
 * Decides whether a hi-res track may reach Android as 32-bit float.
 *
 * Float is not a quality switch that is safe on every route. Some OEM speaker paths accept the float
 * AudioTrack and then squash it back to 16-bit in the mixer; on affected Samsung FLAC decoders that
 * combination also produces timestamp discontinuities and distorted sound. So float is only used when
 * it was asked for, the active route is not the phone's own speaker, and that route advertises float.
 */
object OutputPolicy {
    fun shouldUseFloat(mode: OutputPcmMode, route: LosslessOutput.Kind?, advertisesFloat: Boolean): Boolean {
        if (mode != OutputPcmMode.FLOAT_32) return false
        if (route == LosslessOutput.Kind.SPEAKER) return false
        return advertisesFloat
    }

    /** Samsung's vendor FLAC decoder emits invalid timestamps when its output feeds a float sink. */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val n = name.lowercase()
        return n == "c2.sec.flac.decoder" || (n.startsWith("omx.sec.") && n.contains("flac"))
    }
}
