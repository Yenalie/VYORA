package com.vyora.music.data.lyrics

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: List<LyricLine>, val plain: String?) {
    val isSynced: Boolean get() = synced.isNotEmpty()
}

object LyricsRepository {
    private const val TAG = "VYORA-Lyrics"
    private const val BASE = "https://lrclib.net/api"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val CACHE_MAX = 200
    private val cache = object : LinkedHashMap<String, Lyrics?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > CACHE_MAX
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    suspend fun lyricsFor(id: String, rawTitle: String, rawArtist: String, durationSec: Long): Lyrics? {
        synchronized(cache) { if (cache.containsKey(id)) return cache[id] }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val (title, artist) = clean(rawTitle, rawArtist)
                exact(title, artist, durationSec) ?: search(title, artist)
            }
        }
        outcome.exceptionOrNull()?.let { Log.w(TAG, "Lyrics lookup failed", it); return null }
        val result = outcome.getOrNull()
        synchronized(cache) { cache[id] = result }
        return result
    }

    private fun exact(title: String, artist: String, durationSec: Long): Lyrics? {
        if (artist.isBlank()) return null
        val url = "$BASE/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        val body = get(url.toString()) ?: return null
        return parse(JSONObject(body))
    }

    private fun search(title: String, artist: String): Lyrics? {
        val url = "$BASE/search".toHttpUrl().newBuilder()
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
        val request = Request.Builder().url(url).header("User-Agent", "VYORA Music (https://github.com/)").build()
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
        return Lyrics(synced, plain)
    }

    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(lrc: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { line ->
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = line.substring(tags.last().range.last + 1).trim()
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
                out += LyricLine(min * 60_000 + sec * 1000 + ms, text)
            }
        }
        return out.sortedBy { it.timeMs }
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
