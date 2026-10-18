package com.vyora.music.data.lyrics

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Provider implementations adapted from BitChord GPL-3.0, upstream revision edc1e6f36febf71a118e649411d2fc21b70ead45. */
internal object BitChordLyricsProviders {
    data class BiniHit(
        val isrc: String?,
        val lyricsUrl: String?,
        val title: String,
        val artist: String,
        val durationSec: Double,
    )

    private const val LRC_RED = "https://lrc.red/"
    private const val BINI = "https://lyrics-api.binimum.org/"
    private val validIsrc = Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}")
    private val bracketed = Regex("""[(\[][^)\]]*[)\]]""")
    private val whitespace = Regex("\\s+")
    private val combiningMarks = Regex("\\p{Mn}+")
    private val artistSeparators = Regex("""\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)
    private val versionWords = setOf("live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental", "karaoke", "cappella", "acapella", "demo", "edit", "version", "cover", "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended")

    fun documentUrl(isrc: String?): String? {
        val normalized = isrc?.trim()?.uppercase(Locale.ROOT) ?: return null
        return normalized.takeIf(validIsrc::matches)?.let { "${LRC_RED}s/$it.ttml" }
    }

    /** Bini's indexed search identifies the exact recording and commonly returns an lrc.red document URL. */
    suspend fun identifyBini(
        title: String,
        artist: String,
        durationSec: Long,
        album: String? = null,
        versionTitle: String = title,
    ): BiniHit? {
        if (title.isBlank() || artist.isBlank()) return null
        val url = BINI.toHttpUrl().newBuilder()
            .addQueryParameter("track", title)
            .addQueryParameter("artist", artist)
            .apply {
                if (!album.isNullOrBlank()) addQueryParameter("album", album)
                if (durationSec > 0) addQueryParameter("duration", durationSec.toString())
            }
            .build()
        val results = LyricsRepository.getLyricsText(url.toString())
            ?.let { runCatching { JSONObject(it).optJSONArray("results") }.getOrNull() }
            ?: return null
        val hit = (0 until results.length()).asSequence()
            .mapNotNull { results.optJSONObject(it) }
            .firstOrNull { candidate ->
                candidateMatches(
                    candidate.optString("track_name"),
                    candidate.optString("artist_name"),
                    candidate.optDouble("duration", Double.NaN),
                    title,
                    artist,
                    durationSec,
                    versionTitle,
                ) && candidate.optString("lyricsUrl").isNotBlank()
            }
            ?: return null
        return BiniHit(
            isrc = hit.optString("isrc").takeUnless { it.isBlank() || it == "null" },
            lyricsUrl = hit.optString("lyricsUrl").takeUnless { it.isBlank() || it == "null" },
            title = hit.optString("track_name"),
            artist = hit.optString("artist_name"),
            durationSec = hit.optDouble("duration", Double.NaN),
        )
    }

    suspend fun lrcRed(
        title: String,
        artist: String,
        durationSec: Long,
        knownIsrc: String? = null,
        versionTitle: String = title,
    ): Lyrics? {
        documentUrl(knownIsrc)?.let { url ->
            parseTtml(LyricsRepository.getLyricsText(url), "lrc.red")?.let { return it }
        }
        val query = "$title $artist".trim()
        val searchUrl = "$LRC_RED/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val raw = LyricsRepository.getLyricsText(searchUrl.toString()) ?: return null
        val hits = runCatching { JSONObject(raw).optJSONArray("hits") }.getOrNull() ?: return null
        val selected = bestLrcRedHit(hits, title, artist, durationSec, versionTitle) ?: return null
        val url = documentUrl(selected.optString("isrc")) ?: return null
        return parseTtml(LyricsRepository.getLyricsText(url), "lrc.red")
    }

    suspend fun bini(hit: BiniHit?): Lyrics? {
        val url = hit?.lyricsUrl?.let(::safeBiniDocumentUrl) ?: return null
        return parseTtml(LyricsRepository.getLyricsText(url), "BiniLyrics")
    }

    /** Exact-title/artist/version checks avoid accepting another edit from free-text search results. */
    internal fun bestLrcRedHit(
        hits: org.json.JSONArray,
        title: String,
        artist: String,
        durationSec: Long,
        versionTitle: String = title,
    ): JSONObject? {
        val wantedTitle = coreTitle(title)
        val wantedVersion = version(versionTitle)
        val wantedArtists = artist.split(artistSeparators).map(::normalized).filter(String::isNotEmpty).toSet()
        fun distance(hit: JSONObject): Double {
            val duration = hit.optDouble("duration", Double.NaN)
            return if (durationSec > 0 && duration.isFinite()) abs(duration - durationSec) else 0.0
        }
        return (0 until hits.length()).asSequence()
            .mapNotNull { hits.optJSONObject(it) }
            .filter { hit ->
                val hitTitle = hit.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return@filter false
                val hitIsrc = hit.optString("isrc").takeIf { it.isNotBlank() && it != "null" } ?: return@filter false
                val hitArtists = hit.optString("artist").split(artistSeparators).map(::normalized).filter(String::isNotEmpty).toSet()
                candidateMatches(hitTitle, hit.optString("artist"), hit.optDouble("duration", Double.NaN), title, artist, durationSec, versionTitle) &&
                    (wantedArtists.isEmpty() || wantedArtists.any { it in hitArtists }) &&
                    documentUrl(hitIsrc) != null && (durationSec <= 0 || !distance(hit).isFinite() || distance(hit) <= 3.0)
            }
            .minByOrNull(::distance)
    }

    private fun safeBiniDocumentUrl(value: String): String? = runCatching {
        val url = value.toHttpUrl()
        url.takeIf { it.isHttps && it.host in setOf("lrc.red", "www.lrc.red") }?.toString()
    }.getOrNull()

    private fun parseTtml(raw: String?, source: String): Lyrics? {
        val lines = raw?.let(Ttml::parse).orEmpty()
        return lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it, null, source) }
    }

    private fun coreTitle(value: String): String = normalized(value.replace(bracketed, " ").substringBefore(" - ")).ifBlank { normalized(value) }

    private fun candidateMatches(
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Double,
        wantedTitle: String,
        wantedArtist: String,
        wantedDurationSec: Long,
        versionTitle: String,
    ): Boolean {
        if (candidateTitle.isBlank() || coreTitle(candidateTitle) != coreTitle(wantedTitle)) return false
        if (version(candidateTitle) != version(versionTitle)) return false
        val wantedArtists = wantedArtist.split(artistSeparators).map(::normalized).filter(String::isNotEmpty).toSet()
        val candidateArtists = candidateArtist.split(artistSeparators).map(::normalized).filter(String::isNotEmpty).toSet()
        if (wantedArtists.isNotEmpty() && wantedArtists.none { it in candidateArtists }) return false
        return wantedDurationSec <= 0 || !candidateDurationSec.isFinite() || abs(candidateDurationSec - wantedDurationSec) <= 3.0
    }

    private fun version(value: String): Set<String> {
        val extras = bracketed.findAll(value).joinToString(" ") { it.value } + " " + value.substringAfter(" - ", "")
        return normalized(extras).split(' ').filter { it in versionWords }.toSet()
    }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .replace(whitespace, " ")
        .trim()
}

/** LyricsPlus community mirrors: try in parallel and keep the first usable response, as BitChord does. */
internal object LyricsPlusProvider {
    private val mirrors = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )
    private val lastGood = AtomicReference<String?>(null)

    suspend fun lyrics(title: String, artist: String, durationSec: Long, album: String? = null, isrc: String? = null): Lyrics? = coroutineScope {
        val hosts = lastGood.get()?.let { listOf(it) + mirrors.filterNot { host -> host == it } } ?: mirrors
        val pending = hosts.map { host ->
            host to async(Dispatchers.IO) { fetch(host, title, artist, durationSec, album, isrc) }
        }.toMutableList()
        try {
            while (pending.isNotEmpty()) {
                val (host, result) = select {
                    pending.forEach { (candidate, job) -> job.onAwait { candidate to it } }
                }
                pending.removeAll { it.first == host }
                if (result != null) {
                    lastGood.set(host)
                    pending.forEach { it.second.cancel() }
                    return@coroutineScope result
                }
            }
            null
        } catch (cancelled: CancellationException) {
            pending.forEach { it.second.cancel() }
            throw cancelled
        }
    }

    private suspend fun fetch(host: String, title: String, artist: String, durationSec: Long, album: String?, isrc: String?): Lyrics? {
        val url = "$host/v2/lyrics/get".toHttpUrl().newBuilder()
            .addQueryParameter("title", title)
            .addQueryParameter("artist", artist)
            .apply {
                if (durationSec > 0) addQueryParameter("duration", durationSec.toString())
                if (!album.isNullOrBlank()) addQueryParameter("album", album)
                if (!isrc.isNullOrBlank()) addQueryParameter("isrc", isrc)
            }
            .build()
        val raw = LyricsRepository.getLyricsText(url.toString()) ?: return null
        val response = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val lines = parse(response)
        return lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it, null, "LyricsPlus") }
    }

    internal fun parse(response: JSONObject): List<LyricLine> {
        val source = response.optJSONArray("lyrics") ?: return emptyList()
        return (0 until source.length()).mapNotNull { index ->
            val line = source.optJSONObject(index) ?: return@mapNotNull null
            val start = line.optLong("time", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
            val duration = line.optLong("duration", 0L)
            val words = mergeSyllables(line.optJSONArray("syllabus"))
            val text = if (words.isNotEmpty()) words.joinToString(" ") { it.text } else line.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            LyricLine(
                timeMs = words.firstOrNull()?.startMs?.let { minOf(start, it) } ?: start,
                text = text,
                endMs = duration.takeIf { it > 0 }?.let { start + it } ?: -1L,
                words = words,
            )
        }.sortedBy { it.timeMs }
    }

    private data class Run(val start: Long, val end: Long, val text: String)

    private fun mergeSyllables(source: org.json.JSONArray?): List<LyricWord> {
        if (source == null) return emptyList()
        val runs = (0 until source.length()).mapNotNull { index ->
            val item = source.optJSONObject(index) ?: return@mapNotNull null
            val text = item.optString("text")
            val time = item.optLong("time", -1L)
            if (text.isBlank() || time < 0) null else Run(time, time + item.optLong("duration", 0L), text)
        }
        if (runs.isEmpty()) return emptyList()
        val output = mutableListOf<LyricWord>()
        val current = StringBuilder()
        val parts = mutableListOf<LyricSyllable>()
        var wordStart = 0L
        var wordEnd = 0L
        fun flush() {
            val text = current.toString()
            if (text.isNotEmpty()) output += LyricWord(wordStart, maxOf(wordStart, wordEnd), text, LyricSyllable.normalized(parts, text.length))
            current.setLength(0)
            parts.clear()
        }
        runs.forEach { run ->
            val part = run.text.trim()
            if (part.isEmpty()) { flush(); return@forEach }
            val joins = current.isNotEmpty() && run.text.firstOrNull()?.isWhitespace() == false &&
                current.lastOrNull()?.isLetterOrDigit() == true && part.firstOrNull()?.isLetterOrDigit() == true
            if (!joins) flush()
            if (current.isEmpty()) { wordStart = run.start; wordEnd = run.start }
            val from = current.length
            current.append(part)
            parts += LyricSyllable(run.start, maxOf(run.start, run.end), from, current.length)
            wordEnd = maxOf(wordEnd, run.end)
            if (run.text.lastOrNull()?.isWhitespace() == true) flush()
        }
        flush()
        return output
    }
}

/** Community-submitted source with TTML, enhanced/ordinary LRC, and plain-text response variants. */
internal object UnisonProvider {
    suspend fun lyrics(title: String, artist: String, durationSec: Long, album: String? = null): Lyrics? {
        val url = "https://unison.boidu.dev/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("song", title)
            .addQueryParameter("artist", artist)
            .apply {
                if (!album.isNullOrBlank()) addQueryParameter("album", album)
                if (durationSec > 0) addQueryParameter("duration", durationSec.toString())
            }
            .build()
        val body = LyricsRepository.getLyricsText(url.toString()) ?: return null
        val response = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!response.optBoolean("success")) return null
        val data = response.optJSONObject("data") ?: return null
        val text = data.optString("lyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val format = data.optString("format")
        val syncType = data.optString("syncType")
        val lines = when {
            format.equals("ttml", true) -> Ttml.parse(text)
            syncType.equals("plain", true) -> emptyList()
            else -> LyricsRepository.parseLrc(text)
        }
        return if (syncType.equals("plain", true) && !format.equals("ttml", true)) Lyrics(emptyList(), text, "Unison")
        else lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it, null, "Unison") }
    }
}

/** Keyless KuGou catalogue, matching BitChord's hash-first then keyword-fallback flow. */
internal object KuGouProvider {
    private const val DURATION_TOLERANCE_SECONDS = 8

    suspend fun lyrics(title: String, artist: String, durationSec: Long, album: String? = null): Lyrics? {
        if (title.isBlank() || artist.isBlank()) return null
        val keyword = buildString {
            append(title.replace(Regex("""[(（].*?[)）]"""), "").trim().ifBlank { title })
            append(" - ")
            append(artist.replace(Regex("""[(（].*?[)）]"""), "").trim().ifBlank { artist })
            if (!album.isNullOrBlank()) append(" $album")
        }
        val seconds = durationSec.toInt()
        val hashes = searchSongs(keyword, seconds).orEmpty()
        var candidate: JSONObject? = null
        for (hash in hashes) {
            candidate = searchLyrics(hash = hash)?.optJSONObject(0)
            if (candidate != null) break
        }
        if (candidate == null) candidate = searchLyrics(keyword = keyword, seconds = seconds)?.optJSONObject(0)
        candidate ?: return null
        val id = candidate.optString("id").takeIf(String::isNotBlank) ?: return null
        val accessKey = candidate.optString("accesskey").takeIf(String::isNotBlank) ?: return null
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "lrc").addQueryParameter("charset", "utf8")
            .addQueryParameter("client", "pc").addQueryParameter("ver", "1")
            .addQueryParameter("id", id).addQueryParameter("accesskey", accessKey).build()
        val body = LyricsRepository.getLyricsText(url.toString()) ?: return null
        val content = runCatching { JSONObject(body).optString("content") }.getOrNull().orEmpty()
        val lrc = runCatching { String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return null
        val lines = LyricsRepository.parseLrc(stripCredits(lrc))
        return lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it, null, "KuGou") }
    }

    private suspend fun searchSongs(keyword: String, seconds: Int): List<String>? {
        val url = "https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108").addQueryParameter("plat", "0")
            .addQueryParameter("pagesize", "8").addQueryParameter("showtype", "0")
            .addQueryParameter("keyword", keyword).build()
        val body = LyricsRepository.getLyricsText(url.toString()) ?: return null
        val info = runCatching { JSONObject(body).optJSONObject("data")?.optJSONArray("info") }.getOrNull() ?: return null
        return (0 until info.length()).mapNotNull { info.optJSONObject(it) }
            .filter { seconds <= 0 || abs(it.optInt("duration", -1) - seconds) <= DURATION_TOLERANCE_SECONDS }
            .sortedBy { abs(it.optInt("duration", seconds) - seconds) }
            .mapNotNull { it.optString("hash").takeIf(String::isNotBlank) }
    }

    private suspend fun searchLyrics(hash: String? = null, keyword: String? = null, seconds: Int = -1): org.json.JSONArray? {
        val builder = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1").addQueryParameter("man", "yes").addQueryParameter("client", "pc")
        when {
            hash != null -> builder.addQueryParameter("hash", hash)
            keyword != null -> {
                builder.addQueryParameter("keyword", keyword)
                if (seconds > 0) builder.addQueryParameter("duration", (seconds * 1000).toString())
            }
            else -> return null
        }
        val body = LyricsRepository.getLyricsText(builder.build().toString()) ?: return null
        return runCatching { JSONObject(body).optJSONArray("candidates") }.getOrNull()
    }

    private fun stripCredits(raw: String): String {
        val stamped = raw.lineSequence().filter { Regex("""\[\d{2}:\d{2}\.\d{2,3}].*""").matches(it) }.toList()
        if (stamped.isEmpty()) return ""
        val credit = Regex(""".+][^\[]+[:：].+""")
        val head = (minOf(30, stamped.lastIndex) downTo 0).firstOrNull { credit.matches(stamped[it]) }?.plus(1) ?: 0
        val body = stamped.drop(head)
        val tail = (0..minOf(30, body.lastIndex)).firstOrNull { credit.matches(body[body.lastIndex - it]) }?.plus(1) ?: 0
        return body.dropLast(tail).joinToString("\n")
    }
}
