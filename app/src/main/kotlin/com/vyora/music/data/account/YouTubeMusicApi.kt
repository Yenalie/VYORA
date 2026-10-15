package com.vyora.music.data.account

import com.vyora.music.data.youtube.YouTubeConfig
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class SessionExpiredException(message: String) : IOException(message)

object YouTubeMusicApi {
    private const val MAX_PAGES = 40
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(YouTubeConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(YouTubeConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    suspend fun account(cookie: String, authUser: Int = 0): YouTubeAccount? =
        InnerTubeParser.account(post("account/account_menu", JSONObject(), cookie, authUser))?.copy(authUser = authUser)

    data class FullList<T>(val items: List<T>, val complete: Boolean)

    suspend fun likedSongs(): FullList<RemoteSong> = allPages(browse(YouTubeConfig.BROWSE_LIKED_SONGS), InnerTubeParser::songs)

    suspend fun libraryPlaylists(): FullList<RemoteCollection> = allPages(browse(YouTubeConfig.BROWSE_LIBRARY_PLAYLISTS), InnerTubeParser::collections)

    suspend fun libraryAlbums(): FullList<RemoteCollection> = allPages(browse(YouTubeConfig.BROWSE_LIBRARY_ALBUMS), InnerTubeParser::collections)

    data class PlaylistContent(val title: String?, val editable: Boolean, val songs: FullList<RemoteSong>)

    suspend fun playlist(playlistId: String, onlyIfEditable: Boolean = false): PlaylistContent {
        val first = browse(YouTubeConfig.BROWSE_PLAYLIST_PREFIX + playlistId)
        val editable = InnerTubeParser.isEditable(first)
        val songs = if (onlyIfEditable && !editable) FullList(emptyList(), complete = true) else allPages(first, InnerTubeParser::songs)
        return PlaylistContent(title = InnerTubeParser.playlistTitle(first), editable = editable, songs = songs)
    }

    suspend fun like(videoId: String) { post("like/like", JSONObject().put("target", JSONObject().put("videoId", videoId))) }

    suspend fun unlike(videoId: String) { post("like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId))) }

    suspend fun savePlaylist(playlistId: String) { post("like/like", JSONObject().put("target", JSONObject().put("playlistId", playlistId))) }

    suspend fun unsavePlaylist(playlistId: String) { post("like/removelike", JSONObject().put("target", JSONObject().put("playlistId", playlistId))) }

    suspend fun createPlaylist(title: String, videoIds: List<String>): String {
        val body = JSONObject()
            .put("title", title)
            .put("privacyStatus", "PRIVATE")
            .put("videoIds", JSONArray(videoIds))
        return InnerTubeParser.createdPlaylistId(post("playlist/create", body))
            ?: throw IOException("playlist/create returned no id")
    }

    suspend fun deletePlaylist(playlistId: String) { post("playlist/delete", JSONObject().put("playlistId", playlistId)) }

    suspend fun renamePlaylist(playlistId: String, title: String) =
        editPlaylist(playlistId, JSONObject().put("action", "ACTION_SET_PLAYLIST_NAME").put("playlistName", title))

    suspend fun addToPlaylist(playlistId: String, videoId: String) =
        editPlaylist(playlistId, JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId).put("dedupeOption", "DEDUPE_OPTION_SKIP"))

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String) =
        editPlaylist(playlistId, JSONObject().put("action", "ACTION_REMOVE_VIDEO").put("removedVideoId", videoId).put("setVideoId", setVideoId))

    private suspend fun editPlaylist(playlistId: String, action: JSONObject) {
        post("browse/edit_playlist", JSONObject().put("playlistId", playlistId).put("actions", JSONArray().put(action)))
    }

    private suspend fun browse(browseId: String): JSONObject = post("browse", JSONObject().put("browseId", browseId))

    private suspend fun <T> allPages(first: JSONObject, parse: (JSONObject) -> RemotePage<T>): FullList<T> {
        val items = ArrayList<T>()
        var page = parse(first)
        items += page.items
        var pages = 1
        val seen = HashSet<String>()
        while (true) {
            val token = page.continuation ?: return FullList(items, complete = true)
            if (!seen.add(token)) return FullList(items, complete = true)
            if (pages >= MAX_PAGES) return FullList(items, complete = false)
            page = parse(post("browse", JSONObject().put("continuation", token)))
            items += page.items
            pages++
        }
    }

    private suspend fun post(
        endpoint: String,
        body: JSONObject,
        cookie: String = AccountStore.cookie() ?: throw SessionExpiredException("Not signed in"),
        authUser: Int = AccountStore.account.value?.authUser ?: 0,
    ): JSONObject = withContext(Dispatchers.IO) {
        body.put("context", context())
        val request = Request.Builder()
            .url(YouTubeConfig.INNERTUBE_BASE + endpoint + "?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", YouTubeConfig.BROWSER_USER_AGENT)
            .header("Origin", YouTubeConfig.MUSIC_ORIGIN)
            .header("X-Origin", YouTubeConfig.MUSIC_ORIGIN)
            .header("Referer", YouTubeConfig.MUSIC_ORIGIN + "/")
            .header("X-YouTube-Client-Name", YouTubeConfig.MUSIC_CLIENT_ID)
            .header("X-YouTube-Client-Version", YouTubeConfig.MUSIC_CLIENT_VERSION)
            .header("X-Goog-AuthUser", authUser.toString())
            .header("Cookie", cookie)
            .apply { InnerTubeAuth.authorization(cookie)?.let { header("Authorization", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.code == 401 || response.code == 403 -> throw SessionExpiredException("HTTP ${response.code}")
                !response.isSuccessful -> throw IOException("HTTP ${response.code} on $endpoint")
            }
            val json = runCatching { JSONObject(text) }.getOrElse { throw IOException("Bad JSON from $endpoint") }
            if (json.optJSONObject("responseContext")?.let(::isLoggedOut) == true && endpoint != "account/account_menu") {
                throw SessionExpiredException("Logged out")
            }
            json
        }
    }

    private fun isLoggedOut(ctx: JSONObject): Boolean {
        val params = ctx.optJSONArray("serviceTrackingParams") ?: return false
        for (i in 0 until params.length()) {
            val list = params.optJSONObject(i)?.optJSONArray("params") ?: continue
            for (j in 0 until list.length()) {
                val p = list.optJSONObject(j) ?: continue
                if (p.optString("key") == "logged_in" && p.optString("value") == "0") return true
            }
        }
        return false
    }

    private fun context(): JSONObject {
        val locale = Locale.getDefault()
        return JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", YouTubeConfig.MUSIC_CLIENT_NAME)
                .put("clientVersion", YouTubeConfig.MUSIC_CLIENT_VERSION)
                .put("hl", locale.language.ifBlank { "en" })
                .put("gl", locale.country.ifBlank { "US" }),
        )
    }
}

internal object InnerTubeAuth {
    fun authorization(cookie: String, nowSeconds: Long = System.currentTimeMillis() / 1000, origin: String = YouTubeConfig.MUSIC_ORIGIN): String? {
        val cookies = parseCookies(cookie)
        val sapisid = cookies[YouTubeConfig.SAPISID_COOKIE] ?: cookies[YouTubeConfig.SECURE_SAPISID_COOKIE] ?: return null
        return "SAPISIDHASH ${nowSeconds}_${sha1("$nowSeconds $sapisid $origin")}"
    }

    fun parseCookies(cookie: String): Map<String, String> =
        cookie.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

    fun hasSession(cookie: String?): Boolean {
        if (cookie.isNullOrBlank()) return false
        val c = parseCookies(cookie)
        return c.containsKey(YouTubeConfig.SAPISID_COOKIE) || c.containsKey(YouTubeConfig.SECURE_SAPISID_COOKIE)
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
