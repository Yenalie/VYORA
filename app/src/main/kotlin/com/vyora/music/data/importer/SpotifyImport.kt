package com.vyora.music.data.importer

import com.vyora.music.core.Http
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.SearchFilter
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Imports a public Spotify playlist or album into a VYORA playlist: the track list is read from Spotify's public
 * embed page (no login, no API key), then every track is matched to the closest song on YouTube Music.
 */
object SpotifyImport {
    data class Track(val title: String, val artist: String, val durationMs: Long)
    data class Source(val kind: String, val id: String)
    data class Listing(val name: String, val tracks: List<Track>)

    sealed interface Progress {
        data object Idle : Progress
        data object Reading : Progress
        data class Matching(val done: Int, val total: Int) : Progress
        data class Done(val playlistId: String, val name: String, val matched: Int, val total: Int) : Progress
        data class Failed(val reason: Reason) : Progress
    }

    enum class Reason { BAD_LINK, NOT_FOUND, EMPTY, NETWORK }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val link = Regex("""(?:open\.spotify\.com/(?:intl-[a-z-]+/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{10,40})""")

    fun parse(url: String): Source? = link.find(url.trim())?.let { Source(it.groupValues[1], it.groupValues[2]) }

    private val nextData = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Reads the embed page's JSON. Pure, for tests. */
    fun parseEmbed(html: String): Listing? {
        val json = nextData.find(html)?.groupValues?.get(1) ?: return null
        val entity = runCatching {
            JSONObject(json).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
                .getJSONObject("data").getJSONObject("entity")
        }.getOrNull() ?: return null
        val list = entity.optJSONArray("trackList") ?: return Listing(entity.optString("name"), emptyList())
        val tracks = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            Track(title, t.optString("subtitle").replace('\u00a0', ' ').trim(), t.optLong("duration"))
        }
        return Listing(entity.optString("name").ifBlank { entity.optString("title") }, tracks)
    }

    fun reset() { _progress.value = Progress.Idle }

    suspend fun import(url: String): Progress {
        val source = parse(url) ?: return Progress.Failed(Reason.BAD_LINK).also { _progress.value = it }
        _progress.value = Progress.Reading
        val listing = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://open.spotify.com/embed/${source.kind}/${source.id}")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                    .build()
                Http.base.newCall(req).execute().use { r ->
                    if (r.code == 404) return@runCatching null
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    parseEmbed(r.body?.string().orEmpty())
                }
            }
        }
        val l = listing.getOrElse { return Progress.Failed(Reason.NETWORK).also { _progress.value = it } }
            ?: return Progress.Failed(Reason.NOT_FOUND).also { _progress.value = it }
        if (l.tracks.isEmpty()) return Progress.Failed(Reason.EMPTY).also { _progress.value = it }

        var done = 0
        _progress.value = Progress.Matching(0, l.tracks.size)
        val gate = Semaphore(4)
        val matched: List<SimpleItem?> = coroutineScope {
            l.tracks.map { track ->
                async {
                    gate.withPermit {
                        match(track).also {
                            synchronized(this@SpotifyImport) { done++ }
                            _progress.value = Progress.Matching(done, l.tracks.size)
                        }
                    }
                }
            }.awaitAll()
        }
        val songs = matched.filterNotNull()
        if (songs.isEmpty()) return Progress.Failed(Reason.NETWORK).also { _progress.value = it }
        val id = LibraryManager.createPlaylistWith(l.name.ifBlank { "Spotify" }, songs)
            ?: return Progress.Failed(Reason.NETWORK).also { _progress.value = it }
        return Progress.Done(id, l.name, songs.size, l.tracks.size).also { _progress.value = it }
    }

    private suspend fun match(track: Track): SimpleItem? {
        val firstArtist = track.artist.split(',').first().trim()
        val results = YouTubeClient.search("${track.title} $firstArtist", SearchFilter.SONGS).getOrNull()?.items.orEmpty()
            .filter { it.isSong }
        if (results.isEmpty()) return null
        return results.take(5).maxByOrNull { score(track, it) }
    }

    /** Prefers the same title, the same artist and a length within a few seconds. */
    fun score(track: Track, candidate: SimpleItem): Int {
        fun norm(s: String) = s.lowercase().replace(Regex("""\(.*?\)|\[.*?]"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        var score = 0
        val t = norm(track.title); val c = norm(candidate.title)
        if (t == c) score += 50 else if (c.contains(t) || t.contains(c)) score += 25
        val artists = track.artist.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (artists.any { candidate.artist.lowercase().contains(it) }) score += 30
        if (track.durationMs > 0 && candidate.duration > 0) {
            val diff = kotlin.math.abs(track.durationMs / 1000 - candidate.duration)
            score += when { diff <= 3 -> 20; diff <= 10 -> 10; diff > 30 -> -20; else -> 0 }
        }
        return score
    }
}
