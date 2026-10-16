package com.vyora.music.data.spotify

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.vyora.music.core.Http
import com.vyora.music.core.SecureBox
import com.vyora.music.data.importer.SpotifyImport
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class SpotifyProfile(val id: String, val name: String, val avatarUrl: String?)

data class SpotifyPlaylistInfo(
    val uri: String,
    val name: String,
    val imageUrl: String?,
    val owner: String?,
    val total: Int?,
)

data class SpotifyTrackInfo(
    val uri: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val imageUrl: String?,
    val durationMs: Long,
) {
    val artist: String get() = artists.joinToString(", ")
    fun toImport() = SpotifyImport.Track(title, artist, durationMs)
}

data class SpotifyPage<T>(val items: List<T>, val total: Int)

class SpotifyException(val code: Int, message: String) : Exception(message)

/**
 * Spotify sign-in and library access, ported from YumaPlayer's `spotifycore` (GPL-3.0).
 *
 * Login happens in a WebView on accounts.spotify.com; once Spotify sets the `sp_dc` session cookie VYORA keeps
 * it (encrypted, in [SecureBox]). A web-player access token is then minted from open.spotify.com/api/token
 * with the TOTP the web player uses, and the user's profile, playlists and Liked Songs are read through the
 * same persisted GraphQL queries the web player sends. Audio is never taken from Spotify: tracks are matched
 * to YouTube Music, like the link importer does.
 *
 * These are private web-player endpoints; they can change without notice.
 */
object SpotifyAccount {
    private const val TAG = "VYORA-Spotify"
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val NUANCE_URL =
        "https://gist.githubusercontent.com/sonic-liberation/22ed9c6ba463899e933427f7de1f0eef/raw/nuances.json"
    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    const val LIKED_URI = "spotify:collection:tracks"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    private const val PREFS = "vyora_spotify"
    private const val KEY_SP_DC = "spotify_sp_dc"
    private const val KEY_SP_KEY = "spotify_sp_key"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_AVATAR = "avatar"

    /** Persisted-query hashes of the web player (from Yuma's SpotifyHashProvider). */
    internal val HASHES = mapOf(
        "profileAttributes" to "53bcb064f6cd18c23f752bc324a791194d20df612d8e1239c735144ab0399ced",
        "libraryV3" to "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
        "fetchPlaylist" to "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
    )

    private var appContext: Context? = null
    private val tokenLock = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var tokenExpires = 0L
    @Volatile private var nuance: Pair<String, Int>? = null
    @Volatile private var nuanceAt = 0L

    private val _profile = MutableStateFlow<SpotifyProfile?>(null)
    val profile: StateFlow<SpotifyProfile?> = _profile.asStateFlow()

    private val client: OkHttpClient by lazy {
        Http.base.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = prefs() ?: return
        val id = p.getString(KEY_ID, null)
        if (id != null && SecureBox.get(KEY_SP_DC) != null) {
            _profile.value = SpotifyProfile(id, p.getString(KEY_NAME, null) ?: id, p.getString(KEY_AVATAR, null))
        }
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val isSignedIn: Boolean get() = _profile.value != null

    /** Stores the cookies captured from the login WebView (or typed in) and loads the profile. */
    suspend fun signIn(spDc: String, spKey: String = ""): Result<SpotifyProfile> = withContext(Dispatchers.IO) {
        val dc = spDc.trim().trim('"')
        if (dc.length < 20 || dc.any { it.isWhitespace() || it == ';' }) {
            return@withContext Result.failure(IllegalArgumentException("Invalid sp_dc"))
        }
        runCatching {
            token = null
            val access = mintToken(dc, spKey.trim())
            val me = fetchProfile(access)
            SecureBox.put(KEY_SP_DC, dc)
            SecureBox.put(KEY_SP_KEY, spKey.trim().ifBlank { null })
            prefs()?.edit {
                putString(KEY_ID, me.id)
                putString(KEY_NAME, me.name)
                putString(KEY_AVATAR, me.avatarUrl)
            }
            _profile.value = me
            me
        }.onFailure { Log.w(TAG, "Spotify sign-in failed", it); token = null }
    }

    fun signOut() {
        SecureBox.remove(KEY_SP_DC)
        SecureBox.remove(KEY_SP_KEY)
        prefs()?.edit { clear() }
        token = null
        tokenExpires = 0L
        _profile.value = null
    }

    suspend fun playlists(offset: Int = 0, limit: Int = 50): SpotifyPage<SpotifyPlaylistInfo> {
        val vars = JSONObject()
            .put("filters", JSONArray().put("Playlists"))
            .put("order", JSONObject.NULL)
            .put("textFilter", "")
            .put("features", JSONArray(listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS")))
            .put("limit", limit)
            .put("offset", offset)
            .put("flatten", true)
            .put("expandedFolders", JSONArray())
            .put("folderUri", JSONObject.NULL)
            .put("includeFoldersWhenFlattening", false)
        return parsePlaylists(query("libraryV3", vars))
    }

    suspend fun likedSongs(offset: Int = 0, limit: Int = 50): SpotifyPage<SpotifyTrackInfo> =
        parseLikedSongs(query("fetchLibraryTracks", JSONObject().put("offset", offset).put("limit", limit)))

    /** Playlist header and one page of its tracks. */
    suspend fun playlist(uri: String, offset: Int = 0, limit: Int = 100): Pair<String, SpotifyPage<SpotifyTrackInfo>> {
        val vars = JSONObject().put("uri", uri).put("offset", offset).put("limit", limit).put("enableWatchFeedEntrypoint", false)
        val json = query("fetchPlaylist", vars)
        val playlist = json.optJSONObject("data")?.optJSONObject("playlistV2")
            ?: throw SpotifyException(500, "Invalid fetchPlaylist response")
        return playlist.optString("name") to parsePlaylistTracks(json)
    }

    /** Every track of a playlist (or Liked Songs for [LIKED_URI]), page by page, up to [max]. */
    suspend fun allTracks(uri: String, max: Int = 2_000, onPage: (Int, Int) -> Unit = { _, _ -> }): Pair<String, List<SpotifyTrackInfo>> {
        val out = mutableListOf<SpotifyTrackInfo>()
        var name = ""
        var total: Int
        do {
            val page = if (uri == LIKED_URI) {
                likedSongs(out.size, 50)
            } else {
                val (n, p) = playlist(uri, out.size, 100)
                name = n
                p
            }
            total = page.total
            if (page.items.isEmpty()) break
            out += page.items
            onPage(out.size, total)
        } while (out.size < total && out.size < max)
        return name to out
    }

    // ---- HTTP -----------------------------------------------------------------------------------------

    private suspend fun query(operation: String, variables: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            val access = accessToken()
            val body = gqlBody(operation, variables).toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
            val request = Request.Builder().url(GQL_URL).post(body)
                .header("Authorization", "Bearer $access")
                .header("User-Agent", USER_AGENT)
                .header("app-platform", "WebPlayer")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .header("Accept", "application/json")
                .header("Accept-Language", Locale.getDefault().toLanguageTag())
                .build()
            client.newCall(request).execute().use { r ->
                when {
                    r.code == 401 -> { token = null; lastError = SpotifyException(401, "Token expired") }
                    r.code == 429 -> {
                        lastError = SpotifyException(429, "Rate limited")
                        delay((r.header("Retry-After")?.toLongOrNull() ?: (2L * (attempt + 1))).coerceAtMost(8) * 1000)
                    }
                    r.code == 412 -> throw SpotifyException(412, "Spotify changed $operation (hash rotated)")
                    !r.isSuccessful -> throw SpotifyException(r.code, "Spotify HTTP ${r.code}")
                    else -> {
                        val json = JSONObject(r.body?.string().orEmpty())
                        val error = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                        if (error != null) throw SpotifyException(if ("PersistedQueryNotFound" in error) 412 else 400, error)
                        return@withContext json
                    }
                }
            }
        }
        throw lastError ?: SpotifyException(500, "Spotify request failed")
    }

    internal fun gqlBody(operation: String, variables: JSONObject): JSONObject = JSONObject()
        .put("variables", variables)
        .put("operationName", operation)
        .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", HASHES.getValue(operation))))

    private suspend fun accessToken(): String = tokenLock.withLock {
        token?.takeIf { System.currentTimeMillis() < tokenExpires - 60_000L }?.let { return@withLock it }
        val dc = SecureBox.get(KEY_SP_DC) ?: throw SpotifyException(401, "Not signed in to Spotify")
        mintToken(dc, SecureBox.get(KEY_SP_KEY).orEmpty())
    }

    private suspend fun mintToken(spDc: String, spKey: String): String {
        val (secret, version) = nuance()
        val serverTime = JSONObject(get(SERVER_TIME_URL, emptyMap())).getLong("serverTime")
        val code = totp(secret, serverTime)
        val url = "$TOKEN_URL?reason=transport&productType=web-player&totp=$code&totpServer=$code&totpVer=$version"
        val cookie = "sp_dc=$spDc" + if (spKey.isNotEmpty()) "; sp_key=$spKey" else ""
        val json = JSONObject(get(url, mapOf("Cookie" to cookie)))
        val access = json.optString("accessToken")
        if (json.optBoolean("isAnonymous", false) || access.isBlank()) {
            throw SpotifyException(401, "Spotify session cookie is invalid or expired")
        }
        token = access
        tokenExpires = json.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 30 * 60_000L)
        return access
    }

    private fun nuance(): Pair<String, Int> {
        nuance?.takeIf { System.currentTimeMillis() - nuanceAt < 5 * 60_000L }?.let { return it }
        val fresh = runCatching { pickNuance(JSONArray(get(NUANCE_URL, emptyMap()))) }.getOrNull()
        val chosen = fresh ?: nuance ?: throw SpotifyException(503, "Could not fetch Spotify TOTP secret")
        nuance = chosen
        nuanceAt = System.currentTimeMillis()
        return chosen
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw SpotifyException(r.code, "HTTP ${r.code}")
            return text
        }
    }

    private fun fetchProfile(access: String): SpotifyProfile {
        val body = gqlBody("profileAttributes", JSONObject()).toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        val request = Request.Builder().url(GQL_URL).post(body)
            .header("Authorization", "Bearer $access")
            .header("User-Agent", USER_AGENT)
            .header("app-platform", "WebPlayer")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw SpotifyException(r.code, "Spotify HTTP ${r.code}")
            return parseProfile(JSONObject(r.body?.string().orEmpty()))
        }
    }

    // ---- TOTP (SpotifyTotpGenerator) --------------------------------------------------------------------

    internal fun pickNuance(list: JSONArray): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val s = o.optString("s")
            val v = o.optInt("v", 0)
            if (v > 0 && s.matches(Regex("^[A-Z2-7]+=*$")) && (best == null || v > best.second)) best = s to v
        }
        return best
    }

    internal fun totp(secret: String, serverTimeSec: Long): String {
        val step = serverTimeSec / 30L
        val msg = ByteArray(8) { i -> (step shr (8 * (7 - i)) and 0xFF).toByte() }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(base32(secret), "HmacSHA1")) }
        val hash = mac.doFinal(msg)
        val offset = hash.last().toInt() and 0x0F
        val code = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    internal fun base32(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input.uppercase().replace("=", "")) {
            val v = alphabet.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    // ---- Parsers (SpotifyParsers / SpotifyTrackParser / SpotifyPlaylistParser) ---------------------------

    private fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    /** Largest image of a GraphQL `sources` array. */
    private fun bestImage(sources: JSONArray?): String? {
        if (sources == null) return null
        var url: String? = null
        var size = -1
        for (i in 0 until sources.length()) {
            val o = sources.optJSONObject(i) ?: continue
            val u = o.str("url") ?: continue
            val w = o.optInt("width", 0)
            if (w > size) { size = w; url = u }
        }
        return url
    }

    internal fun parseProfile(json: JSONObject): SpotifyProfile {
        val p = json.obj("data")?.obj("me")?.obj("profile") ?: throw SpotifyException(500, "Invalid profile response")
        val uri = p.str("uri").orEmpty()
        val id = uri.substringAfterLast(':')
        return SpotifyProfile(id, p.str("name") ?: id, bestImage(p.obj("avatar")?.optJSONArray("sources")))
    }

    internal fun parsePlaylists(json: JSONObject): SpotifyPage<SpotifyPlaylistInfo> {
        val lib = json.obj("data")?.obj("me")?.obj("libraryV3") ?: throw SpotifyException(500, "Invalid libraryV3 response")
        val items = lib.optJSONArray("items") ?: JSONArray()
        val out = (0 until items.length()).mapNotNull { i ->
            val wrapper = items.optJSONObject(i)?.obj("item") ?: return@mapNotNull null
            if (wrapper.str("__typename") != "PlaylistResponseWrapper") return@mapNotNull null
            val data = wrapper.obj("data") ?: return@mapNotNull null
            if (data.str("__typename") != "Playlist") return@mapNotNull null
            val uri = wrapper.str("_uri") ?: return@mapNotNull null
            val images = data.obj("images")?.optJSONArray("items")
            SpotifyPlaylistInfo(
                uri = uri,
                name = data.str("name").orEmpty(),
                imageUrl = images?.optJSONObject(0)?.optJSONArray("sources")?.let(::bestImage),
                owner = data.obj("ownerV2")?.obj("data")?.str("name"),
                total = data.obj("content")?.intOrNull("totalCount") ?: data.intOrNull("totalCount"),
            )
        }
        return SpotifyPage(out, lib.optInt("totalCount", out.size))
    }

    internal fun parseTrack(data: JSONObject, uriOverride: String?): SpotifyTrackInfo? {
        val uri = uriOverride ?: data.str("uri") ?: data.str("_uri") ?: return null
        val title = data.str("name") ?: return null
        val artistItems = data.obj("artists")?.optJSONArray("items") ?: JSONArray()
        val artists = (0 until artistItems.length()).mapNotNull { artistItems.optJSONObject(it)?.obj("profile")?.str("name") }
        val album = data.obj("albumOfTrack")
        val duration = data.obj("trackDuration")?.optLong("totalMilliseconds")?.takeIf { it > 0 }
            ?: data.obj("duration")?.optLong("totalMilliseconds")?.takeIf { it > 0 }
            ?: 0L
        return SpotifyTrackInfo(
            uri = uri,
            title = title,
            artists = artists,
            album = album?.str("name").orEmpty(),
            imageUrl = bestImage(album?.obj("coverArt")?.optJSONArray("sources")),
            durationMs = duration,
        )
    }

    internal fun parseLikedSongs(json: JSONObject): SpotifyPage<SpotifyTrackInfo> {
        val tracks = json.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
            ?: throw SpotifyException(500, "Invalid fetchLibraryTracks response")
        val items = tracks.optJSONArray("items") ?: JSONArray()
        val out = (0 until items.length()).mapNotNull { i ->
            val wrapper = items.optJSONObject(i)?.obj("track") ?: return@mapNotNull null
            val data = wrapper.obj("data") ?: return@mapNotNull null
            parseTrack(data, wrapper.str("_uri") ?: wrapper.str("uri"))
        }
        return SpotifyPage(out, tracks.optInt("totalCount", out.size))
    }

    internal fun parsePlaylistTracks(json: JSONObject): SpotifyPage<SpotifyTrackInfo> {
        val content = json.obj("data")?.obj("playlistV2")?.obj("content")
            ?: throw SpotifyException(500, "No content in fetchPlaylist response")
        val items = content.optJSONArray("items") ?: JSONArray()
        val out = (0 until items.length()).mapNotNull { i ->
            val wrapper = items.optJSONObject(i)?.obj("itemV2") ?: return@mapNotNull null
            val data = wrapper.obj("data") ?: return@mapNotNull null
            // Podcast episodes and local files show up here too; only real tracks can be matched.
            if (data.str("__typename")?.let { it != "Track" } == true) return@mapNotNull null
            parseTrack(data, wrapper.str("_uri") ?: wrapper.str("uri"))
        }
        return SpotifyPage(out, content.optInt("totalCount", out.size))
    }
}
