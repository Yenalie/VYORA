package com.vyora.music.data.motion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vyora.music.core.Http
import com.vyora.music.data.spotify.SpotifyAccount
import com.vyora.music.data.youtube.SimpleItem
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** Where a motion loop comes from; decides how the full player frames it. */
enum class MotionSource { APPLE_TALL, APPLE_SQUARE, SPOTIFY_CANVAS }

/**
 * A looping, silent video that stands in for a song's artwork, like Apple Music's animated album covers or
 * Spotify's Canvas. [aspect] is width / height of the video.
 */
data class MotionArt(val url: String, val source: MotionSource, val aspect: Float, val background: Int? = null)

/** Fuzzy title/artist matching shared by the Apple and Spotify lookups. */
object MotionMatch {
    private val noise = Regex(
        "\\s*[(\\[][^)\\]]*(official|video|audio|lyric|lirik|visualizer|mv|m/v|live|remaster|feat|ft\\.|with )[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val feat = Regex("\\s+(feat\\.?|ft\\.?|featuring|with)\\s+.*$", RegexOption.IGNORE_CASE)

    fun normalize(s: String): String {
        val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.lowercase(Locale.ROOT)
            .replace(noise, "")
            .replace(feat, "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }

    fun artists(s: String): List<String> = s.split(Regex("\\s*(,|&| x | and |feat\\.?|ft\\.?|·)\\s*", RegexOption.IGNORE_CASE))
        .map(::normalize).filter { it.isNotEmpty() }

    /**
     * 0..100. A good match needs the same title (after dropping "(Official Video)" style noise) and at least
     * one shared artist; a duration that is far off pulls the score down, since remixes and live cuts share
     * titles.
     */
    fun score(title: String, artist: String, candTitle: String, candArtist: String, durationMs: Long, candMs: Long): Int {
        val t = normalize(title)
        val ct = normalize(candTitle)
        if (t.isEmpty() || ct.isEmpty()) return 0
        var score = when {
            t == ct -> 60
            ct.startsWith(t) || t.startsWith(ct) -> 45
            t in ct || ct in t -> 35
            else -> return 0
        }
        val mine = artists(artist)
        val theirs = artists(candArtist)
        score += when {
            mine.isEmpty() -> 0
            mine.firstOrNull() == theirs.firstOrNull() -> 30
            mine.any { a -> theirs.any { b -> a == b || a in b || b in a } } -> 22
            else -> -40
        }
        if (durationMs > 0 && candMs > 0) {
            val diff = kotlin.math.abs(durationMs - candMs)
            score += when {
                diff <= 3_000 -> 10
                diff <= 10_000 -> 4
                diff > 25_000 -> -25
                else -> 0
            }
        }
        return score.coerceIn(0, 100)
    }

    const val ACCEPT = 75
}

/**
 * Finds motion artwork for a song. Apple Music first (its catalog exposes the animated cover as
 * `editorialVideo` on the album), then Spotify Canvas when the listener is signed in to Spotify.
 * Answers, including "nothing here", are cached so a song is only looked up once in a while.
 */
object MotionArtwork {
    private const val TAG = "MotionArtwork"
    private const val PREFS = "motion_artwork"
    private const val KEY_TOKEN = "apple_token"
    private const val HIT_TTL_MS = 14L * 24 * 3600 * 1000
    private const val MISS_TTL_MS = 3L * 24 * 3600 * 1000
    private const val MAX_ENTRIES = 600
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private var prefs: SharedPreferences? = null
    private val memory = ConcurrentHashMap<String, Pair<Long, MotionArt?>>()
    private val tokenLock = Mutex()
    @Volatile private var appleToken: String? = null

    private val client by lazy { Http.with(10, 15) }

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun key(item: SimpleItem) = MotionMatch.normalize(item.title) + "|" + MotionMatch.normalize(item.artist)

    /** Cached answer without any network, or null when the song has not been looked up yet. */
    fun cached(item: SimpleItem): Result<MotionArt?>? {
        val k = key(item)
        val now = System.currentTimeMillis()
        memory[k]?.let { (until, art) -> if (until > now) return Result.success(art) }
        val raw = prefs?.getString("m:$k", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val until = o.getLong("until")
            if (until <= now) return null
            val art = o.optJSONObject("art")?.let {
                MotionArt(
                    it.getString("url"), MotionSource.valueOf(it.getString("source")), it.getDouble("aspect").toFloat(),
                    if (it.has("bg")) it.getInt("bg") else null,
                )
            }
            memory[k] = until to art
            art
        }
    }

    private fun remember(item: SimpleItem, art: MotionArt?) {
        val k = key(item)
        val until = System.currentTimeMillis() + if (art != null) HIT_TTL_MS else MISS_TTL_MS
        memory[k] = until to art
        val p = prefs ?: return
        val o = JSONObject().put("until", until)
        if (art != null) {
            o.put("art", JSONObject().put("url", art.url).put("source", art.source.name).put("aspect", art.aspect.toDouble())
                .apply { art.background?.let { put("bg", it) } })
        }
        val editor = p.edit()
        if (p.all.size > MAX_ENTRIES) p.all.keys.filter { it.startsWith("m:") }.forEach(editor::remove)
        editor.putString("m:$k", o.toString()).apply()
    }

    /**
     * Motion artwork for [item], or null. [metered] picks a lighter rendition (Apple ships each loop in
     * several sizes) so mobile data is not spent on pixels the screen cannot show.
     */
    suspend fun find(item: SimpleItem, metered: Boolean): MotionArt? {
        if (item.isVideo || item.title.isBlank()) return null
        cached(item)?.let { return it.getOrNull() }
        val art = withContext(Dispatchers.IO) {
            val apple = runCatching { apple(item, metered) }
                .onFailure { Log.w(TAG, "Apple lookup failed: ${it.message}") }
            val found = apple.getOrNull() ?: runCatching {
                SpotifyAccount.canvasFor(item.title, item.artist, item.duration * 1000L)
                    ?.let { MotionArt(it, MotionSource.SPOTIFY_CANVAS, 9f / 16f) }
            }.onFailure { Log.w(TAG, "Canvas lookup failed: ${it.message}") }.getOrNull()
            // Only cache a miss when the lookup actually answered; a network error should retry next time.
            if (found != null || apple.isSuccess) remember(item, found)
            found
        }
        return art
    }

    // ---- Apple Music ------------------------------------------------------------------------------------

    private fun storefront(): String = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"

    private suspend fun apple(item: SimpleItem, metered: Boolean): MotionArt? {
        val durationMs = item.duration * 1000L
        val fronts = listOf(storefront(), "us").distinct()
        for (sf in fronts) {
            val album = appleAlbumId(item.title, item.artist, durationMs, sf) ?: continue
            val token = appleToken() ?: return null
            val json = get(
                "https://amp-api.music.apple.com/v1/catalog/$sf/albums/$album?extend=editorialVideo",
                mapOf("Authorization" to "Bearer $token", "Origin" to "https://music.apple.com"),
            ) ?: continue
            val art = parseAppleAlbum(JSONObject(json)) ?: return null
            val master = get(art.url) ?: return null
            val mp4 = pickRendition(master, if (metered) 500 else 700) ?: return null
            return art.copy(url = mp4)
        }
        return null
    }

    private fun appleAlbumId(title: String, artist: String, durationMs: Long, storefront: String): Long? {
        val term = "${MotionMatch.normalize(title)} ${MotionMatch.artists(artist).firstOrNull().orEmpty()}".trim()
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .addQueryParameter("country", storefront)
            .build().toString()
        val body = get(url) ?: return null
        return bestITunesAlbum(JSONObject(body), title, artist, durationMs)
    }

    internal fun bestITunesAlbum(json: JSONObject, title: String, artist: String, durationMs: Long): Long? {
        val results = json.optJSONArray("results") ?: return null
        var best: Long? = null
        var bestScore = 0
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            if (r.optString("kind") != "song") continue
            val s = MotionMatch.score(title, artist, r.optString("trackName"), r.optString("artistName"), durationMs, r.optLong("trackTimeMillis"))
            if (s > bestScore) { bestScore = s; best = r.optLong("collectionId").takeIf { it > 0 } }
        }
        return best.takeIf { bestScore >= MotionMatch.ACCEPT }
    }

    /** The tall (3:4) loop when the album has one, otherwise the square one. URL still points at the HLS master. */
    internal fun parseAppleAlbum(json: JSONObject): MotionArt? {
        val ev = json.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")?.optJSONObject("editorialVideo")
            ?: return null
        fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k -> ev.optJSONObject(k)?.takeIf { it.optString("video").startsWith("https://") } }
        val tall = pick("motionDetailTall", "motionTallVideo3x4")
        val square = if (tall == null) pick("motionDetailSquare", "motionSquareVideo1x1") else null
        val chosen = tall ?: square ?: return null
        val bg = chosen.optJSONObject("previewFrame")?.optString("bgColor")?.takeIf { it.length == 6 }
            ?.let { runCatching { (0xFF000000 or it.toLong(16)).toInt() }.getOrNull() }
        return MotionArt(
            url = chosen.getString("video"),
            source = if (tall != null) MotionSource.APPLE_TALL else MotionSource.APPLE_SQUARE,
            aspect = if (tall != null) 3f / 4f else 1f,
            background = bg,
        )
    }

    /**
     * From an HLS master playlist, the H.264 rendition whose width is closest to (and not above) [maxWidth],
     * as its progressive mp4 twin: Apple publishes each `..._-.m3u8` variant also as `..._-.mp4`, which plays
     * and caches as one small file with no HLS stack.
     */
    internal fun pickRendition(master: String, maxWidth: Int): String? {
        data class V(val width: Int, val url: String)
        val variants = mutableListOf<V>()
        val lines = master.lines()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            if (!line.contains("CODECS=\"avc1")) continue
            if (line.contains("VIDEO-RANGE=PQ") || line.contains("VIDEO-RANGE=HLG")) continue
            val width = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val url = lines.getOrNull(i + 1)?.trim()?.takeIf { it.startsWith("https://") && it.endsWith("_-.m3u8") } ?: continue
            variants += V(width, url.removeSuffix(".m3u8") + ".mp4")
        }
        if (variants.isEmpty()) return null
        val fit = variants.filter { it.width <= maxWidth }
        return (fit.maxByOrNull { it.width } ?: variants.minByOrNull { it.width })?.url
    }

    /**
     * Apple's public web-player developer token, read from music.apple.com's bundle (the same token every
     * visitor of the site gets) and reused until its own expiry.
     */
    private suspend fun appleToken(): String? = tokenLock.withLock {
        appleToken?.takeIf { tokenValid(it) }?.let { return it }
        prefs?.getString(KEY_TOKEN, null)?.takeIf { tokenValid(it) }?.let { appleToken = it; return it }
        val html = get("https://music.apple.com/us/browse") ?: return null
        val scripts = Regex("(/assets/index[^\"']*\\.js)").findAll(html).map { it.groupValues[1] }.distinct().toList()
        for (path in scripts) {
            val js = get("https://music.apple.com$path") ?: continue
            val token = Regex("eyJ0eXAiOiJKV1Qi[A-Za-z0-9_\\-]*\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+").find(js)?.value ?: continue
            if (!tokenValid(token)) continue
            appleToken = token
            prefs?.edit()?.putString(KEY_TOKEN, token)?.apply()
            return token
        }
        null
    }

    internal fun tokenExpiry(token: String): Long? = runCatching {
        val payload = token.split('.')[1]
        val json = String(java.util.Base64.getUrlDecoder().decode(payload.trimEnd('=')))
        JSONObject(json).getLong("exp") * 1000L
    }.getOrNull()

    private fun tokenValid(token: String): Boolean = (tokenExpiry(token) ?: 0L) > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { r ->
            if (r.code == 401 || r.code == 403) {
                if (url.contains("amp-api")) { appleToken = null; prefs?.edit()?.remove(KEY_TOKEN)?.apply() }
                return null
            }
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }
}
