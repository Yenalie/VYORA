package com.vyora.music.data.local

import java.util.Locale

/** Apple Music-style quality tiers. */
enum class AudioTier { LOSSY, LOSSLESS, HI_RES_LOSSLESS }

/**
 * What is actually being decoded (or stored in a file): codec, resolution and bitrate.
 * Unknown numeric fields are 0.
 */
data class AudioFormatInfo(
    val mimeType: String,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrate: Int = 0,
    val channels: Int = 0,
) {
    val isLossless: Boolean get() = AudioQuality.isLosslessMime(mimeType)
    val tier: AudioTier get() = AudioQuality.tier(mimeType, sampleRateHz, bitDepth)
    val codec: String get() = AudioQuality.codecLabel(mimeType)

    /** e.g. "24-bit / 96 kHz", "16-bit / 44.1 kHz", "Opus · 160 kbps". */
    fun resolutionLabel(): String {
        val parts = mutableListOf<String>()
        if (isLossless) {
            if (bitDepth > 0) parts += "$bitDepth-bit"
            if (sampleRateHz > 0) parts += AudioQuality.kiloHertz(sampleRateHz)
            if (parts.isEmpty()) parts += codec
            return parts.joinToString(" / ")
        }
        parts += codec
        if (bitrate > 0) parts += "${(bitrate + 500) / 1000} kbps"
        return parts.joinToString(" · ")
    }
}

object AudioQuality {
    /** Apple's "Hi-Res Lossless" threshold: anything above 48 kHz. */
    const val HI_RES_MIN_SAMPLE_RATE = 48_001

    private val LOSSLESS_MIMES = setOf(
        "audio/flac", "audio/x-flac",
        "audio/alac", "audio/x-alac",
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
        "audio/aiff", "audio/x-aiff",
        "audio/raw",
        "audio/x-ape", "audio/ape",
        "audio/x-wavpack", "audio/wavpack",
    )

    fun isLosslessMime(mime: String?): Boolean = mime?.lowercase(Locale.ROOT)?.trim() in LOSSLESS_MIMES

    fun tier(mime: String?, sampleRateHz: Int, bitDepth: Int): AudioTier = when {
        !isLosslessMime(mime) -> AudioTier.LOSSY
        sampleRateHz >= HI_RES_MIN_SAMPLE_RATE -> AudioTier.HI_RES_LOSSLESS
        else -> AudioTier.LOSSLESS
    }

    fun codecLabel(mime: String?): String {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "flac" in m -> "FLAC"
            "alac" in m -> "ALAC"
            "wav" in m || "wave" in m -> "WAV"
            "aiff" in m -> "AIFF"
            "ape" in m -> "APE"
            "wavpack" in m -> "WavPack"
            m == "audio/raw" -> "PCM"
            "opus" in m -> "Opus"
            "vorbis" in m || "ogg" in m -> "Vorbis"
            "mp4a" in m || "aac" in m || m == "audio/mp4" || "m4a" in m -> "AAC"
            "mpeg" in m || "mp3" in m -> "MP3"
            "webm" in m -> "Opus"
            else -> m.substringAfter('/').uppercase(Locale.ROOT).ifBlank { "Audio" }
        }
    }

    fun kiloHertz(hz: Int): String {
        val khz = hz / 1000.0
        return if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.US, "%.1f kHz", khz)
    }
}

/**
 * Tiny header parsers so the library can tell 16/44.1 from 24/96 without spinning up a decoder.
 * They only read the first few dozen bytes of a file.
 */
object AudioHeaders {
    const val PROBE_BYTES = 64

    /** FLAC: "fLaC" + metadata block header + STREAMINFO (always the first block). */
    fun parseFlac(head: ByteArray): AudioFormatInfo? {
        if (head.size < 8 + 34) return null
        if (head[0] != 'f'.code.toByte() || head[1] != 'L'.code.toByte() ||
            head[2] != 'a'.code.toByte() || head[3] != 'C'.code.toByte()
        ) return null
        if ((head[4].toInt() and 0x7F) != 0) return null // first block must be STREAMINFO
        val si = 8
        fun u(i: Int) = head[si + i].toInt() and 0xFF
        val sampleRate = (u(10) shl 12) or (u(11) shl 4) or (u(12) ushr 4)
        val channels = ((u(12) ushr 1) and 0x07) + 1
        val bitsPerSample = (((u(12) and 0x01) shl 4) or (u(13) ushr 4)) + 1
        if (sampleRate <= 0) return null
        return AudioFormatInfo("audio/flac", sampleRate, bitsPerSample, 0, channels)
    }

    /** RIFF/WAVE with a "fmt " chunk (searched within the probed bytes). */
    fun parseWav(head: ByteArray): AudioFormatInfo? {
        if (head.size < 36) return null
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var p = 12
        while (p + 8 <= head.size) {
            val id = String(head, p, 4, Charsets.US_ASCII)
            val len = le32(head, p + 4)
            if (id == "fmt " && p + 8 + 16 <= head.size) {
                val channels = le16(head, p + 10)
                val sampleRate = le32(head, p + 12)
                val bits = le16(head, p + 22)
                return AudioFormatInfo("audio/wav", sampleRate, bits, sampleRate * channels * bits, channels)
            }
            if (len < 0) return null
            p += 8 + len + (len and 1)
        }
        return null
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
}
