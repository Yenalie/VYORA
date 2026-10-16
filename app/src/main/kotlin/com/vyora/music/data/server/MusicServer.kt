package com.vyora.music.data.server

import android.content.Context
import com.vyora.music.core.Http
import com.vyora.music.core.SecureBox
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lossless streaming from the user's own music server over the Subsonic API (Navidrome, Gonic, Airsonic,
 * Ampache, LMS…). Songs are streamed as the original file (`format=raw`), so FLAC/ALAC/WAV arrive bit for bit
 * and ExoPlayer decodes them at their native bit depth and sample rate.
 *
 * Ids are `srv:<songId>` for songs and `srvalbum:<albumId>` for albums so they never collide with YouTube ids.
 */
object MusicServer {
    const val SONG_PREFIX = "srv:"
    const val ALBUM_PREFIX = "srvalbum:"
    private const val API_VERSION = "1.16.1"
    private const val CLIENT = "VYORA"

    private const val K_URL = "server_url"
    private const val K_USER = "server_user"
    private const val K_PASS = "server_pass"
    private const val K_SALT = "server_salt"
    private const val K_NAME = "server_name"

    data class Config(val baseUrl: HttpUrl, val user: String, val token: String, val salt: String, val name: String)

    private val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = _config.asStateFlow()
    val isConfigured: Boolean get() = _config.value != null

    private val client by lazy { Http.with(10, 30) }

    fun init(context: Context) {
        SecureBox.init(context)
        load()
    }

    private fun load() {
        val url = SecureBox.get(K_URL)?.let(::normalize)
        val user = SecureBox.get(K_USER)
        val pass = SecureBox.get(K_PASS)
        val salt = SecureBox.get(K_SALT)
        _config.value = if (url != null && user != null && pass != null && salt != null) {
            Config(url, user, token(pass, salt), salt, SecureBox.get(K_NAME).orEmpty())
        } else null
    }

    fun normalize(raw: String): HttpUrl? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return null
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return withScheme.toHttpUrlOrNull()
    }

    /** Subsonic token auth: md5(password + salt); the password itself never goes over the wire. */
    fun token(password: String, salt: String): String =
        MessageDigest.getInstance("MD5").digest((password + salt).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun newSalt(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Checks the server and saves it when it answers. Returns the server type/version for display. */
    suspend fun connect(url: String, user: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalize(url) ?: error("URL")
            val salt = newSalt()
            val cfg = Config(base, user.trim(), token(password, salt), salt, "")
            val res = call(cfg, "ping")
            val name = listOfNotNull(res.optString("type").takeIf { it.isNotBlank() }, res.optString("serverVersion").takeIf { it.isNotBlank() })
                .joinToString(" ").ifBlank { "Subsonic ${res.optString("version")}" }
            SecureBox.put(K_URL, base.toString())
            SecureBox.put(K_USER, user.trim())
            SecureBox.put(K_PASS, password)
            SecureBox.put(K_SALT, salt)
            SecureBox.put(K_NAME, name)
            load()
            name
        }
    }

    fun disconnect() {
        listOf(K_URL, K_USER, K_PASS, K_SALT, K_NAME).forEach(SecureBox::remove)
        _config.value = null
    }

    private fun HttpUrl.Builder.auth(cfg: Config): HttpUrl.Builder = this
        .addQueryParameter("u", cfg.user)
        .addQueryParameter("t", cfg.token)
        .addQueryParameter("s", cfg.salt)
        .addQueryParameter("v", API_VERSION)
        .addQueryParameter("c", CLIENT)

    private fun endpoint(cfg: Config, method: String): HttpUrl.Builder =
        cfg.baseUrl.newBuilder().addPathSegment("rest").addPathSegment(method).auth(cfg)

    private fun call(cfg: Config, method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = endpoint(cfg, method).addQueryParameter("f", "json").apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val root = JSONObject(r.body?.string().orEmpty()).getJSONObject("subsonic-response")
            if (root.optString("status") != "ok") {
                error(root.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "Server error")
            }
            return root
        }
    }

    /** The original file, untouched; `null` until a server is set up. */
    fun streamUrl(songId: String): String {
        val cfg = _config.value ?: return "vyora-server://unconfigured/$songId"
        return endpoint(cfg, "stream").addQueryParameter("id", songId).addQueryParameter("format", "raw")
            .addQueryParameter("estimateContentLength", "true").build().toString()
    }

    fun coverUrl(coverId: String?, size: Int = 600): String? {
        val cfg = _config.value ?: return null
        if (coverId.isNullOrBlank()) return null
        return endpoint(cfg, "getCoverArt").addQueryParameter("id", coverId).addQueryParameter("size", size.toString()).build().toString()
    }

    fun isSongId(id: String?) = id != null && id.startsWith(SONG_PREFIX)
    fun isAlbumId(id: String?) = id != null && id.startsWith(ALBUM_PREFIX)
    fun songIdOf(id: String) = id.removePrefix(SONG_PREFIX)

    data class ServerSong(val item: SimpleItem, val suffix: String, val bitRate: Int, val bitDepth: Int, val sampleRate: Int) {
        val lossless: Boolean get() = suffix.lowercase() in LOSSLESS_SUFFIXES
    }

    val LOSSLESS_SUFFIXES = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")

    private fun song(o: JSONObject): ServerSong {
        val id = o.getString("id")
        return ServerSong(
            item = SimpleItem(
                id = SONG_PREFIX + id,
                title = o.optString("title"),
                artist = o.optString("artist"),
                thumbnail = coverUrl(o.optString("coverArt").ifBlank { o.optString("albumId") }),
                url = streamUrl(id),
                duration = o.optLong("duration"),
                type = ItemType.SONG,
            ),
            suffix = o.optString("suffix"),
            bitRate = o.optInt("bitRate"),
            bitDepth = o.optInt("bitDepth"),
            sampleRate = o.optInt("samplingRate"),
        )
    }

    private fun album(o: JSONObject): SimpleItem = SimpleItem(
        id = ALBUM_PREFIX + o.getString("id"),
        title = o.optString("name").ifBlank { o.optString("title") },
        artist = o.optString("artist"),
        thumbnail = coverUrl(o.optString("coverArt").ifBlank { o.optString("id") }),
        url = "",
        type = ItemType.ALBUM,
        count = o.optLong("songCount", -1L),
    )

    private fun JSONObject.arrayOrSingle(key: String): List<JSONObject> = when (val v = opt(key)) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> listOf(v)
        else -> emptyList()
    }

    private fun need(): Config = _config.value ?: error("No server")

    suspend fun albums(type: String = "newest", size: Int = 60): Result<List<SimpleItem>> = io {
        call(need(), "getAlbumList2", mapOf("type" to type, "size" to size.toString()))
            .optJSONObject("albumList2")?.arrayOrSingle("album").orEmpty().map(::album)
    }

    suspend fun albumSongs(albumId: String): Result<List<ServerSong>> = io {
        call(need(), "getAlbum", mapOf("id" to albumId.removePrefix(ALBUM_PREFIX)))
            .optJSONObject("album")?.arrayOrSingle("song").orEmpty().map(::song)
    }

    suspend fun randomSongs(size: Int = 50): Result<List<ServerSong>> = io {
        call(need(), "getRandomSongs", mapOf("size" to size.toString()))
            .optJSONObject("randomSongs")?.arrayOrSingle("song").orEmpty().map(::song)
    }

    data class SearchResult(val songs: List<ServerSong>, val albums: List<SimpleItem>)

    suspend fun search(query: String): Result<SearchResult> = io {
        val r = call(need(), "search3", mapOf("query" to query, "songCount" to "40", "albumCount" to "20", "artistCount" to "0"))
            .optJSONObject("searchResult3")
        SearchResult(r?.arrayOrSingle("song").orEmpty().map(::song), r?.arrayOrSingle("album").orEmpty().map(::album))
    }

    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }
}
