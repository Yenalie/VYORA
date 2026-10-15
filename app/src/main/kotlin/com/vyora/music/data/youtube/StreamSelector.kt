package com.vyora.music.data.youtube

import com.vyora.music.data.settings.AudioQuality

/** One playable audio rendition of a video, reduced to what the choice depends on. */
data class AudioCandidate(
    val itag: Int,
    val url: String,
    val bitrate: Int,
    val codec: String,
    val mimeType: String,
    /** YouTube "Stable volume" rendition: dynamic-range compressed, sounds flat and muffled. */
    val isDrc: Boolean,
    val trackKind: TrackKind,
    val progressive: Boolean,
    val contentLength: Long,
)

/** One video-only rendition, used for the moving artwork of music videos. */
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
    /** The original soundtrack (or the only one). */
    ORIGINAL,
    /** Dubbed, described (audio description) or otherwise secondary language tracks. */
    SECONDARY,
}

/**
 * Picks renditions. Pure logic, so it is unit tested without the network.
 *
 * Audio rules, in order:
 *  1. progressive HTTP only (ExoPlayer opens it straight from the watch-URL item);
 *  2. never a DRC ("Stable volume") rendition when a normal one exists. They share the itag
 *     family and bitrate of the normal ones, so the old "highest bitrate" pick sometimes
 *     landed on them: that is what made some songs sound suddenly flat and muffled;
 *  3. the original track over dubbed / audio-description tracks;
 *  4. the quality setting decides the bitrate; Opus wins ties (better at the same bitrate);
 *  5. [preferredItag] (what the media cache already holds for this song) wins when it still
 *     passes 1-3, so a re-resolve never mixes bytes of two different files.
 */
object StreamSelector {
    /** NORMAL picks the best rendition at or below this bitrate. */
    const val NORMAL_MAX_BITRATE = 140_000

    /** LOW still avoids the ~30-50 kbps renditions, which sound like a phone call. */
    const val LOW_MIN_BITRATE = 60_000

    /** Moving artwork never needs more than this; it is drawn small and costs data. */
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

    /**
     * The sharpest video-only rendition up to [maxHeight], H.264 first (then VP9, then AV1),
     * the smaller file when two are equally sharp.
     */
    fun pickVideo(all: List<VideoCandidate>, maxHeight: Int = VIDEO_MAX_HEIGHT): VideoCandidate? {
        val playable = all.filter { it.progressive && it.url.isNotBlank() && it.height in 1..maxHeight }
        if (playable.isEmpty()) return null
        return playable.sortedWith(
            compareBy<VideoCandidate> { it.codecRank }.thenBy { it.height }.thenBy { -it.bitrate }
        ).last()
    }

    private val AudioCandidate.isOpus get() = codec.contains("opus", ignoreCase = true)
    /** H.264 decodes everywhere in hardware, VP9 almost everywhere, AV1 only on recent chips. */
    private val VideoCandidate.codecRank: Int
        get() = when {
            codec.startsWith("avc", ignoreCase = true) -> 2
            codec.startsWith("vp9", ignoreCase = true) || codec.startsWith("vp09", ignoreCase = true) -> 1
            codec.isBlank() && mimeType.contains("mp4") -> 2
            else -> 0
        }
}
