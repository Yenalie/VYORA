package com.vyora.music.data.youtube

import com.vyora.music.data.settings.AudioQuality

data class AudioCandidate(
    val itag: Int,
    val url: String,
    val bitrate: Int,
    val codec: String,
    val mimeType: String,
    val isDrc: Boolean,
    val trackKind: TrackKind,
    val progressive: Boolean,
    val contentLength: Long,
)

data class VideoCandidate(
    val itag: Int,
    val url: String,
    val height: Int,
    val bitrate: Int,
    val codec: String,
    val mimeType: String,
    val progressive: Boolean,
)

enum class TrackKind {
    ORIGINAL,
    SECONDARY,
}

object StreamSelector {
    const val NORMAL_MAX_BITRATE = 140_000

    const val LOW_MIN_BITRATE = 60_000

    const val VIDEO_MAX_HEIGHT = 480

    fun pickAudio(all: List<AudioCandidate>, quality: AudioQuality, preferredItag: Int? = null): AudioCandidate? {
        val playable = all.filter { it.progressive && it.url.isNotBlank() && it.bitrate > 0 }
        if (playable.isEmpty()) return null
        val clean = playable.filterNot { it.isDrc }.ifEmpty { playable }
        val pool = clean.filter { it.trackKind == TrackKind.ORIGINAL }.ifEmpty { clean }

        preferredItag?.let { itag -> pool.firstOrNull { it.itag == itag }?.let { return it } }

        val ranked = pool.sortedWith(compareBy<AudioCandidate> { it.bitrate }.thenBy { if (it.isOpus) 1 else 0 })
        return when (quality) {
            AudioQuality.HIGH -> ranked.last()
            AudioQuality.NORMAL -> ranked.lastOrNull { it.bitrate <= NORMAL_MAX_BITRATE } ?: ranked.first()
            AudioQuality.LOW -> ranked.firstOrNull { it.bitrate >= LOW_MIN_BITRATE } ?: ranked.last()
        }
    }

    fun pickVideo(all: List<VideoCandidate>, maxHeight: Int = VIDEO_MAX_HEIGHT): VideoCandidate? {
        val playable = all.filter { it.progressive && it.url.isNotBlank() && it.height in 1..maxHeight }
        if (playable.isEmpty()) return null
        return playable.sortedWith(
            compareBy<VideoCandidate> { it.codecRank }.thenBy { it.height }.thenBy { -it.bitrate }
        ).last()
    }

    private val AudioCandidate.isOpus get() = codec.contains("opus", ignoreCase = true)
    private val VideoCandidate.codecRank: Int
        get() = when {
            codec.startsWith("avc", ignoreCase = true) -> 2
            codec.startsWith("vp9", ignoreCase = true) || codec.startsWith("vp09", ignoreCase = true) -> 1
            codec.isBlank() && mimeType.contains("mp4") -> 2
            else -> 0
        }
}
