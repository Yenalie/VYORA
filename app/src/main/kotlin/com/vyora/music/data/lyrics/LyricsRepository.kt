package com.vyora.music.data.lyrics

import android.content.Context
import android.util.Log
import com.vyora.music.core.Http
import com.vyora.music.data.db.CacheDao
import com.vyora.music.data.db.LyricsCacheEntity
import com.vyora.music.data.db.VyoraDatabase
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Callback
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object LyricsRepository {
    private const val TAG = "VYORA-Lyrics"
    private const val LRCLIB = "https://lrclib.net/api"
    private const val BETTER_LYRICS = "https://lyrics-api.boidu.dev/getLyrics"
    private const val BETTER_LYRICS_PORTATO = "https://lyrics-api.boidu.dev/qq/getLyrics"

    /** Negative results are retried after three days; unsynced text is refreshed every six hours. */
    private const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val PLAIN_TTL_MS = 6L * 60 * 60 * 1000
    private const val SYNCED_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val DISK_KEEP = 3000
    private const val IDENTIFY_TIMEOUT_MS = 2_500L

    private val client by lazy { Http.with(8, 12) }

    private const val CACHE_MAX = 200
    private data class CachedLyrics(val value: Lyrics?, val fetchedAt: Long)
    private val cache = object : LinkedHashMap<String, CachedLyrics>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?) = size > CACHE_MAX
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
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val cached = cache[id]
            if (cached != null) {
                if (isFresh(cached.value, cached.fetchedAt)) return cached.value
                cache.remove(id)
            }
        }
        val disk = dao
        val stored = disk?.let { d -> runCatching { withContext(Dispatchers.IO) { d.lyrics(id) } }.getOrNull() }
        if (stored != null) {
            val decoded = stored.payload?.let(LyricsJson::decode)
            val age = (now - stored.fetchedAt).coerceAtLeast(0L)
            val currentPayload = stored.payload == null || LyricsJson.version(stored.payload) >= LyricsJson.CACHE_VERSION
            if (currentPayload && isFresh(decoded, stored.fetchedAt, age)) {
                synchronized(cache) { cache[id] = CachedLyrics(decoded, stored.fetchedAt) }
                return decoded
            }
        }
        val result = try {
            val (title, artist) = clean(rawTitle, rawArtist)
            fetch(title, artist, durationSec, rawTitle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Lyrics lookup failed", error)
            null
        }
        val fetchedAt = System.currentTimeMillis()
        synchronized(cache) { cache[id] = CachedLyrics(result, fetchedAt) }
        if (disk != null) withContext(Dispatchers.IO) {
            runCatching {
                disk.putLyrics(LyricsCacheEntity(id, result?.let(LyricsJson::encode), fetchedAt))
                disk.trimLyrics(DISK_KEEP)
            }
        }
        return result
    }

    private fun isFresh(value: Lyrics?, fetchedAt: Long, age: Long = (System.currentTimeMillis() - fetchedAt).coerceAtLeast(0L)): Boolean =
        age < when {
            value == null -> MISS_TTL_MS
            value.isSynced -> SYNCED_TTL_MS
            else -> PLAIN_TTL_MS
        }

    /**
     * BitChord's keyless default chain, adapted to VYORA's Android lyric model:
     * lrc.red/BiniLyrics, Apple TTML, QQ karaoke, LyricsPlus mirrors, Unison,
     * KuGou, then LRCLIB. Network requests race concurrently, but results are
     * accepted in the same deterministic source priority; a word-timed result
     * beats a line-only fallback.
     */
    private suspend fun fetch(title: String, artist: String, durationSec: Long, originalTitle: String): Lyrics? = coroutineScope {
        val biniIdentity = async(Dispatchers.IO) {
            runCatching { BitChordLyricsProviders.identifyBini(title, artist, durationSec, versionTitle = originalTitle) }
                .onFailure { Log.d(TAG, "BiniLyrics identify: ${it.message}") }
                .getOrNull()
        }
        val identified = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) { biniIdentity.await() }

        val attempts = listOf(
            "lrc.red" to async(Dispatchers.IO) {
                runCatching { BitChordLyricsProviders.lrcRed(title, artist, durationSec, identified?.isrc, originalTitle) }
                    .onFailure { Log.d(TAG, "lrc.red: ${it.message}") }.getOrNull()
            },
            "BiniLyrics" to async(Dispatchers.IO) {
                runCatching { BitChordLyricsProviders.bini(identified ?: biniIdentity.await()) }
                    .onFailure { Log.d(TAG, "BiniLyrics: ${it.message}") }.getOrNull()
            },
            "BetterLyrics" to async(Dispatchers.IO) {
                runCatching { betterLyrics(title, artist, durationSec) }
                    .onFailure { Log.d(TAG, "BetterLyrics: ${it.message}") }.getOrNull()
            },
            "BetterLyrics Portato" to async(Dispatchers.IO) {
                runCatching { betterLyrics(title, artist, durationSec, BETTER_LYRICS_PORTATO, SOURCE_BETTER_LYRICS_PORTATO) }
                    .onFailure { Log.d(TAG, "BetterLyrics Portato: ${it.message}") }.getOrNull()
            },
            "LyricsPlus" to async(Dispatchers.IO) {
                runCatching { LyricsPlusProvider.lyrics(title, artist, durationSec) }
                    .onFailure { Log.d(TAG, "LyricsPlus: ${it.message}") }.getOrNull()
            },
            "Unison" to async(Dispatchers.IO) {
                runCatching { UnisonProvider.lyrics(title, artist, durationSec) }
                    .onFailure { Log.d(TAG, "Unison: ${it.message}") }.getOrNull()
            },
            "KuGou" to async(Dispatchers.IO) {
                runCatching { KuGouProvider.lyrics(title, artist, durationSec) }
                    .onFailure { Log.d(TAG, "KuGou: ${it.message}") }.getOrNull()
            },
            "LRCLIB" to async(Dispatchers.IO) {
                runCatching { exact(title, artist, durationSec) ?: search(title, artist) }
                    .onFailure { Log.d(TAG, "LRCLIB: ${it.message}") }.getOrNull()
            },
        )

        var lineSynced: Lyrics? = null
        var plainFallback: Lyrics? = null
        try {
            for ((source, job) in attempts) {
                val found = try {
                    job.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.d(TAG, "$source lookup failed: ${error.message}")
                    null
                } ?: continue
                if (found.isWordSynced) return@coroutineScope found
                if (found.synced.any { it.timeMs > 0L }) {
                    if (lineSynced == null) lineSynced = found
                } else if (plainFallback == null && !found.plain.isNullOrBlank()) {
                    plainFallback = found
                }
            }
            lineSynced ?: plainFallback
        } finally {
            attempts.forEach { it.second.cancel() }
            biniIdentity.cancel()
        }
    }

    private suspend fun betterLyrics(
        title: String,
        artist: String,
        durationSec: Long,
        endpoint: String = BETTER_LYRICS,
        source: String = SOURCE_BETTER_LYRICS,
    ): Lyrics? {
        if (title.isBlank() || artist.isBlank()) return null
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("s", title)
            .addQueryParameter("a", artist)
            .apply { if (durationSec > 0) addQueryParameter("d", durationSec.toString()) }
            .build()
        val body = getLyricsText(url.toString()) ?: return null
        val lines = PortatoLyrics.parse(body)
        if (lines.isEmpty()) return null
        if (durationSec > 0) {
            val last = lines.maxOf { maxOf(it.timeMs, it.endMs) } / 1000
            if (last > durationSec + 20) return null
        }
        return Lyrics(lines, null, source)
    }

    private suspend fun exact(title: String, artist: String, durationSec: Long): Lyrics? {
        if (artist.isBlank()) return null
        val url = "$LRCLIB/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        val body = getLyricsText(url.toString()) ?: return null
        return parse(JSONObject(body))
    }

    private suspend fun search(title: String, artist: String): Lyrics? {
        val url = "$LRCLIB/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$title $artist".trim())
            .build()
        val body = getLyricsText(url.toString()) ?: return null
        val array = JSONArray(body)
        var plainFallback: Lyrics? = null
        for (i in 0 until minOf(array.length(), 8)) {
            val lyrics = parse(array.getJSONObject(i)) ?: continue
            if (lyrics.isSynced) return lyrics
            if (plainFallback == null) plainFallback = lyrics
        }
        return plainFallback
    }

    internal suspend fun getLyricsText(url: String): String? = suspendCancellableCoroutine { continuation ->
        val request = runCatching {
            Request.Builder().url(url)
                .header("User-Agent", Http.USER_AGENT)
                .header("Accept", "application/json, text/plain, application/ttml+xml, */*")
                .build()
        }.getOrElse { error ->
            continuation.resumeWith(Result.failure(error))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (it.code == 404) {
                        continuation.resumeWith(Result.success(null))
                    } else if (!it.isSuccessful) {
                        continuation.resumeWith(Result.failure(IOException("HTTP ${it.code}")))
                    } else {
                        val body = runCatching { it.body?.string() }.getOrNull()
                        continuation.resumeWith(Result.success(body))
                    }
                }
            }
        })
    }

    private suspend fun parse(o: JSONObject): Lyrics? {
        if (o.optBoolean("instrumental", false)) return null
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let(::parseLrc).orEmpty()
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced.isEmpty() && plain == null) return null
        return Lyrics(synced, plain, SOURCE_LRCLIB)
    }

    const val SOURCE_BETTER_LYRICS = "BetterLyrics"
    const val SOURCE_BETTER_LYRICS_PORTATO = "BetterLyrics Portato"
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
        RegexOption.IGNORE_CASE,
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
