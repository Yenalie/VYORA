package com.vyora.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/*
 * Ported from BitChord (https://github.com/kushagrasinghx/BitChord)
 * app/src/main/java/com/music/bitchord/playback/SpatialAudioProcessor.kt — GPL-3.0.
 */

/**
 * Spatial audio: mid/side stereo widening with a short low-passed cross-feed, inside the player.
 * Not object-based audio — YouTube only ever sends stereo — but it opens the image up on headphones.
 * Stereo 16-bit only; anything else passes through.
 */
@UnstableApi
class SpatialAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = false

    private var delayLeft = FloatArray(0)
    private var delayRight = FloatArray(0)
    private var delayIndex = 0
    private var lowLeft = 0f
    private var lowRight = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val size = (inputAudioFormat.sampleRate * DELAY_MS / 1000f).roundToInt().coerceAtLeast(1)
        if (delayLeft.size != size) {
            delayLeft = FloatArray(size)
            delayRight = FloatArray(size)
        }
        onFlush()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val out = replaceOutputBuffer(frames * BYTES_PER_FRAME)
        val size = delayLeft.size
        if (!enabled || size == 0) {
            val end = inputBuffer.position() + frames * BYTES_PER_FRAME
            val slice = inputBuffer.duplicate().apply { limit(end) }
            out.put(slice)
            inputBuffer.position(inputBuffer.limit())
            out.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())
        var d = delayIndex
        var lpL = lowLeft
        var lpR = lowRight
        repeat(frames) {
            val left = inputBuffer.short / 32768f
            val right = inputBuffer.short / 32768f
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * WIDTH
            var l = mid + side
            var r = mid - side
            lpL += LOWPASS * (delayRight[d] - lpL)
            lpR += LOWPASS * (delayLeft[d] - lpR)
            l += lpL * CROSSFEED
            r += lpR * CROSSFEED
            delayLeft[d] = left
            delayRight[d] = right
            d = (d + 1) % size
            out.putShort(LoudnessProcessor.toShort(l * OUTPUT))
            out.putShort(LoudnessProcessor.toShort(r * OUTPUT))
        }
        inputBuffer.position(inputBuffer.limit())
        delayIndex = d
        lowLeft = lpL
        lowRight = lpR
        out.flip()
    }

    override fun onFlush() {
        delayLeft.fill(0f)
        delayRight.fill(0f)
        delayIndex = 0
        lowLeft = 0f
        lowRight = 0f
    }

    override fun onReset() {
        delayLeft = FloatArray(0)
        delayRight = FloatArray(0)
        onFlush()
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
        const val DELAY_MS = 15
        const val WIDTH = 2.5f
        const val OUTPUT = 0.82f
        const val CROSSFEED = 0.2f
        const val LOWPASS = 0.3f
    }
}
