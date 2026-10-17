package com.vyora.music.data.library

import com.vyora.music.data.youtube.SimpleItem
import java.util.Locale

/*
 * Ported from BitChord (https://github.com/kushagrasinghx/BitChord)
 * shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueBuilder.kt — GPL-3.0.
 */

/**
 * Turns a raw radio/mix answer into a queue a listener actually wants:
 *
 * - the catalogue song wins over its music-video upload (a YouTube mix pairs nearly every track with its
 *   video under a different id and a title far enough apart that only a normalised match catches it);
 * - the same recording is never queued twice, whatever order its artists are billed in;
 * - no single artist floods the batch — two per artist, four for the artists of the seed itself.
 */
object Recommend {
    const val PER_ARTIST_LIMIT = 2
    const val SEED_ARTIST_LIMIT = 4

    fun extend(
        existing: List<SimpleItem>,
        candidates: List<SimpleItem>,
        limit: Int,
        songsOnly: Boolean = true,
        exclude: Set<String> = emptySet(),
    ): List<SimpleItem> {
        val taken = existing.toMutableList()
        val perArtist = mutableMapOf<String, Int>()
        val seedArtists = existing.lastOrNull()?.artist?.let(::artistSet).orEmpty()
        val out = mutableListOf<SimpleItem>()
        val pool = candidates.filter { it.id !in exclude && it.id.isNotBlank() }
        val ordered = if (songsOnly) pool.filterNot { it.isVideo }.ifEmpty { pool } else pool

        for (candidate in ordered) {
            if (out.size >= limit) break
            if (taken.any { isSameRecording(it, candidate) }) continue
            val artists = artistSet(candidate.artist)
            val key = artists.minOrNull()
            if (key != null) {
                val cap = if (artists.any { it in seedArtists }) SEED_ARTIST_LIMIT else PER_ARTIST_LIMIT
                val played = perArtist[key] ?: 0
                if (played >= cap) continue
                perArtist[key] = played + 1
            }
            taken += candidate
            out += candidate
        }
        return out
    }

    fun isSameRecording(a: SimpleItem, b: SimpleItem): Boolean {
        if (a.id == b.id) return true
        val title = normalisedTitle(a.title)
        if (title.isEmpty() || title != normalisedTitle(b.title)) return false
        val left = artistSet(a.artist)
        val right = artistSet(b.artist)
        return left.isEmpty() || right.isEmpty() || left.any { it in right }
    }

    fun normalisedTitle(raw: String): String = raw.lowercase(Locale.ROOT)
        .substringBefore(" | ")
        .replace(NOISE, " ")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    fun artistSet(raw: String): Set<String> = raw.lowercase(Locale.ROOT)
        .replace(TOPIC, " ")
        .split(",", "&", "·", "•", ";", " feat", " ft.", " ft ", " x ", " with ")
        .map { it.replace(PUNCTUATION, " ").replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * Seeds worth asking a radio for, newest first: the songs at the end of the queue, then what was
     * played most recently and what was liked — so an exhausted station falls back to something related
     * instead of stopping.
     */
    fun seeds(queueTail: List<SimpleItem>, recent: List<SimpleItem>, liked: List<SimpleItem>, max: Int = 4): List<SimpleItem> {
        val out = LinkedHashMap<String, SimpleItem>()
        (queueTail.asReversed() + recent.take(5) + liked.take(5))
            .filter { it.isYouTube && it.isSong }
            .forEach { if (out.size < max) out.putIfAbsent(it.id, it) }
        return out.values.toList()
    }

    private val NOISE = Regex(
        """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k|mv|m/v)[^)]*\)""" +
            """|\(from[^)]*\)""" +
            """|\[[^]]*]""" +
            """|\b(?:official (?:video|audio|music video|mv)|lyrical video|lyric video|full video|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}
