package com.vyora.music.data.lyrics

import android.content.Context
import android.util.Log
import com.vyora.music.core.Http
import com.vyora.music.data.db.CacheDao
import com.vyora.music.data.db.LyricsCacheEntity
import com.vyora.music.data.db.VyoraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object LyricsRepository {
    private const val TAG = "VYORA-Lyrics"
    private const val LRCLIB = "https://lrclib.net/api"
    private const val BETTER_LYRICS = "https://lyrics-api.boidu.dev/getLyrics"

    /** "No lyrics" answers are retried after this long; found lyrics are kept until the cache is cleared. */
    private const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val DISK_KEEP = 3000

    private val client by lazy { Http.with(8, 12) }

    private const val CACHE_MAX = 200
    private val cache = object : LinkedHashMap<String, Lyrics?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > CACHE_MAX
    }

    @Volatile private var dao: CacheDao? = null

    fun init(context: Context) {
        if (dao == null) dao = VyoraDatabase.get(context).cache()
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
        runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) { dao?.clearLyrics() } }
    }

    suspend fun lyricsFor(id: String, rawTitle: String, rawArtist: String, durationSec: Long): Lyrics? {
        synchronized(cache) { if (cache.containsKey(id)) return cache[id] }
        val disk = dao
        val stored = disk?.let { d -> runCatching { withContext(Dispatchers.IO) { d.lyrics(id) } }.getOrNull() }
        if (stored != null) {
            val decoded = stored.payload?.let(LyricsJson::decode)
            val fresh = decoded != null || System.currentTimeMillis() - stored.fetchedAt < MISS_TTL_MS
            if (fresh) {
                synchronized(cache) { cache[id] = decoded }
                return decoded
            }
        }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val (title, artist) = clean(rawTitle, rawArtist)
                fetch(title, artist, durationSec)
            }
        }
        outcome.exceptionOrNull()?.let { Log.w(TAG, "Lyrics lookup failed", it); return null }
        val result = outcome.getOrNull()
        synchronized(cache) { cache[id] = result }
        if (disk != null) withContext(Dispatchers.IO) {
            runCatching {
                disk.putLyrics(LyricsCacheEntity(id, result?.let(LyricsJson::encode), System.currentTimeMillis()))
                disk.trimLyrics(DISK_KEEP)
            }
        }
        return result
    }

    /**
     * Word-synced first (BetterLyrics TTML), then LRCLIB's exact match, then LRCLIB search. A provider that fails
     * or times out just hands over to the next one.
     */
    private fun fetch(title: String, artist: String, durationSec: Long): Lyrics? {
        val better = runCatching { betterLyrics(title, artist, durationSec) }
            .onFailure { Log.d(TAG, "BetterLyrics: ${it.message}") }.getOrNull()
        if (better?.isWordSynced == true) return better
        val lrclib = runCatching { exact(title, artist, durationSec) ?: search(title, artist) }
            .onFailure { Log.d(TAG, "LRCLIB: ${it.message}") }.getOrNull()
        return when {
            better?.isSynced == true -> better
            lrclib?.isSynced == true -> lrclib
            else -> better ?: lrclib
        }
    }

    private fun betterLyrics(title: String, artist: String, durationSec: Long): Lyrics? {
        if (title.isBlank() || artist.isBlank()) return null
        val url = BETTER_LYRICS.toHttpUrl().newBuilder()
            .addQueryParameter("s", title)
            .addQueryParameter("a", artist)
            .apply { if (durationSec > 0) addQueryParameter("d", durationSec.toString()) }
            .build()
        val body = get(url.toString()) ?: return null
        val ttml = runCatching { JSONObject(body).optString("ttml") }.getOrNull()?.takeIf { it.contains("<tt") }
            ?: body.takeIf { it.trimStart().startsWith("<") } ?: return null
        val lines = Ttml.parse(ttml)
        if (lines.isEmpty()) return null
        // A match whose length is far from the song's is the wrong recording.
        if (durationSec > 0) {
            val last = lines.maxOf { maxOf(it.timeMs, it.endMs) } / 1000
            if (last > durationSec + 20) return null
        }
        return Lyrics(lines, null, SOURCE_BETTER_LYRICS)
    }

    private fun exact(title: String, artist: String, durationSec: Long): Lyrics? {
        if (artist.isBlank()) return null
        val url = "$LRCLIB/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        val body = get(url.toString()) ?: return null
        return parse(JSONObject(body))
    }

    private fun search(title: String, artist: String): Lyrics? {
        val url = "$LRCLIB/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$title $artist".trim())
            .build()
        val body = get(url.toString()) ?: return null
        val array = JSONArray(body)
        var plainFallback: Lyrics? = null
        for (i in 0 until minOf(array.length(), 8)) {
            val lyrics = parse(array.getJSONObject(i)) ?: continue
            if (lyrics.isSynced) return lyrics
            if (plainFallback == null) plainFallback = lyrics
        }
        return plainFallback
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string()
        }
    }

    private fun parse(o: JSONObject): Lyrics? {
        if (o.optBoolean("instrumental", false)) return null
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let(::parseLrc).orEmpty()
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced.isEmpty() && plain == null) return null
        return Lyrics(synced, plain, SOURCE_LRCLIB)
    }

    const val SOURCE_BETTER_LYRICS = "BetterLyrics"
    const val SOURCE_LRCLIB = "LRCLIB"

    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(lrc: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { line ->
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val rest = line.substring(tags.last().range.last + 1)
            val words = enhancedWords(rest)
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim() else rest.trim()
            tags.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out += LyricLine(min * 60_000 + sec * 1000 + ms, text, words = words)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private val wordTag = Regex("""<(\d{1,2}):(\d{2})[.:](\d{1,3})>""")

    /** Enhanced LRC (`<00:12.34>word <00:12.80>next`) → timed words; empty for plain lines. */
    internal fun enhancedWords(text: String): List<LyricWord> {
        val marks = wordTag.findAll(text).toList()
        if (marks.isEmpty()) return emptyList()
        fun ms(m: MatchResult): Long {
            val frac = m.groupValues[3]
            val f = when (frac.length) { 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
            return m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + f
        }
        return marks.mapIndexedNotNull { i, m ->
            val from = m.range.last + 1
            val to = marks.getOrNull(i + 1)?.range?.first ?: text.length
            val word = text.substring(from, to)
            if (word.isBlank()) return@mapIndexedNotNull null
            val start = ms(m)
            val end = marks.getOrNull(i + 1)?.let(::ms) ?: (start + 600)
            LyricWord(start, end, word)
        }
    }

    private val noise = Regex(
        """[(\[](?:[^)\]]*?(official|video|audio|lyric|lirik|mv|m/v|visualizer|hd|4k|live|remaster|version|performance)[^)\]]*?)[)\]]""",
        RegexOption.IGNORE_CASE
    )

    fun clean(rawTitle: String, rawArtist: String): Pair<String, String> {
        var title = rawTitle.replace(noise, " ")
            .replace(Regex("""(?i)\b(official\s+(music\s+)?video|lyrics?\s+video|official\s+audio)\b"""), " ")
            .replace(Regex("""\s*\|.*$"""), "")
        var artist = rawArtist.replace(Regex("""(?i)\s*-\s*topic$"""), "").replace(Regex("""(?i)vevo$"""), "").trim()
        val dash = Regex("""\s+[-–—]\s+""").find(title)
        if (dash != null) {
            val left = title.substring(0, dash.range.first).trim()
            val right = title.substring(dash.range.last + 1).trim()
            if (artist.isBlank() || left.contains(artist, ignoreCase = true) || artist.contains(left, ignoreCase = true)) {
                artist = left
                title = right
            }
        }
        title = title.replace(Regex("""(?i)\s*(ft\.?|feat\.?)\s+.*$"""), "")
        return title.replace(Regex("""\s+"""), " ").trim() to artist.replace(Regex("""\s+"""), " ").trim()
    }
}
