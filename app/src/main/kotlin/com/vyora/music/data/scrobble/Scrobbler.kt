package com.vyora.music.data.scrobble

import android.content.Context
import android.util.Log
import com.vyora.music.BuildConfig
import com.vyora.music.core.Http
import com.vyora.music.core.SecureBox
import com.vyora.music.data.db.CacheDao
import com.vyora.music.data.db.PendingScrobbleEntity
import com.vyora.music.data.db.VyoraDatabase
import com.vyora.music.data.lyrics.LyricsRepository
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.youtube.SimpleItem
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sends listens to Last.fm and ListenBrainz. A listen counts by the same rule as Rekap (30 s, or half of a short
 * song), is queued in Room first and sent from there, so nothing is lost while offline.
 */
object Scrobbler {
    private const val TAG = "VYORA-Scrobble"
    const val LASTFM = 1
    const val LISTENBRAINZ = 2

    private const val LASTFM_API = "https://ws.audioscrobbler.com/2.0/"
    private const val LB_API = "https://api.listenbrainz.org/1/"
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private const val K_LFM_KEY = "lastfm_api_key"
    private const val K_LFM_SECRET = "lastfm_secret"
    private const val K_LFM_SESSION = "lastfm_session"
    private const val K_LFM_USER = "lastfm_user"
    private const val K_LB_TOKEN = "lb_token"
    private const val K_LB_USER = "lb_user"

    data class Accounts(val lastFmUser: String? = null, val listenBrainzUser: String? = null)

    private val _accounts = MutableStateFlow(Accounts())
    val accounts: StateFlow<Accounts> = _accounts.asStateFlow()
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private val client by lazy { Http.with(10, 15) }
    private var dao: CacheDao? = null
    private var lastNowPlaying: String? = null

    fun init(context: Context) {
        if (dao != null) return
        SecureBox.init(context)
        dao = VyoraDatabase.get(context).cache()
        refreshAccounts()
        scope.launch { flush() }
    }

    private fun refreshAccounts() {
        _accounts.value = Accounts(
            lastFmUser = SecureBox.get(K_LFM_USER)?.takeIf { SecureBox.get(K_LFM_SESSION) != null },
            listenBrainzUser = SecureBox.get(K_LB_USER)?.takeIf { SecureBox.get(K_LB_TOKEN) != null },
        )
    }

    private fun targets(): Int {
        val s = SettingsRepository.current
        val a = _accounts.value
        var t = 0
        if (s.scrobbleLastFm && a.lastFmUser != null) t = t or LASTFM
        if (s.scrobbleListenBrainz && a.listenBrainzUser != null) t = t or LISTENBRAINZ
        return t
    }

    /** Title/artist as the scrobbling services expect them: no "(Official Video)", no " - Topic". */
    private fun tagsOf(item: SimpleItem): Pair<String, String> = LyricsRepository.clean(item.title, item.artist)

    fun nowPlaying(item: SimpleItem?) {
        if (item == null || !item.isSong) return
        val t = targets()
        if (t == 0 || lastNowPlaying == item.id) return
        lastNowPlaying = item.id
        val (title, artist) = tagsOf(item)
        if (title.isBlank() || artist.isBlank()) return
        scope.launch {
            if (t and LASTFM != 0) runCatching {
                lastFmCall("track.updateNowPlaying", mapOf("artist" to artist, "track" to title) + durationParam(item))
            }.onFailure { Log.d(TAG, "Last.fm now playing: ${it.message}") }
            if (t and LISTENBRAINZ != 0) runCatching {
                lbSubmit("playing_now", listOf(lbListen(null, artist, title, null, item.duration)))
            }.onFailure { Log.d(TAG, "ListenBrainz now playing: ${it.message}") }
        }
    }

    private fun durationParam(item: SimpleItem) =
        if (item.duration > 0) mapOf("duration" to item.duration.toString()) else emptyMap()

    /** Called by the playback service once a song was played long enough to count. */
    fun onListened(item: SimpleItem, listenedMs: Long) {
        if (!item.isSong) return
        val t = targets()
        if (t == 0) return
        val (title, artist) = tagsOf(item)
        if (title.isBlank() || artist.isBlank()) return
        val startedAt = System.currentTimeMillis() - listenedMs
        scope.launch {
            dao?.addScrobble(PendingScrobbleEntity(artist = artist, title = title, album = null, durationSec = item.duration, playedAt = startedAt, targets = t))
            flush()
        }
    }

    suspend fun flush() = sending.withLock {
        val d = dao ?: return@withLock
        runCatching {
            d.pruneScrobbles(System.currentTimeMillis() - MAX_AGE_MS)
            val batch = d.pendingScrobbles(50)
            for (e in batch) {
                var left = e.targets
                if (left and LASTFM != 0 && _accounts.value.lastFmUser != null) {
                    val ok = runCatching {
                        lastFmCall("track.scrobble", buildMap {
                            put("artist", e.artist); put("track", e.title)
                            put("timestamp", (e.playedAt / 1000).toString())
                            if (e.durationSec > 0) put("duration", e.durationSec.toString())
                            e.album?.let { put("album", it) }
                        })
                    }.onFailure { Log.d(TAG, "Last.fm scrobble: ${it.message}") }.isSuccess
                    if (ok) left = left and LASTFM.inv()
                } else left = left and LASTFM.inv()
                if (left and LISTENBRAINZ != 0 && _accounts.value.listenBrainzUser != null) {
                    val ok = runCatching { lbSubmit("single", listOf(lbListen(e.playedAt / 1000, e.artist, e.title, e.album, e.durationSec))) }
                        .onFailure { Log.d(TAG, "ListenBrainz scrobble: ${it.message}") }.isSuccess
                    if (ok) left = left and LISTENBRAINZ.inv()
                } else left = left and LISTENBRAINZ.inv()
                if (left != e.targets) d.setScrobbleTargets(e.id, left)
                if (left != 0) break // offline or rejected: keep order, try again later
            }
            d.pruneScrobbles(System.currentTimeMillis() - MAX_AGE_MS)
            _pending.value = d.pendingScrobbleCount()
        }.onFailure { Log.w(TAG, "Flush failed", it) }
    }

    // ── Last.fm ─────────────────────────────────────────────────────────

    /** Signs in with the user's own Last.fm API account (key + secret) and password; only the session is kept. */
    suspend fun loginLastFm(apiKey: String, secret: String, user: String, password: String): Result<String> = runCatching {
        val params = sortedMapOf("api_key" to apiKey.trim(), "method" to "auth.getMobileSession", "password" to password, "username" to user.trim())
        params["api_sig"] = signature(params, secret.trim())
        params["format"] = "json"
        val json = post(LASTFM_API, params)
        val session = json.optJSONObject("session") ?: error(json.optString("message").ifBlank { "Login failed" })
        SecureBox.put(K_LFM_KEY, apiKey.trim())
        SecureBox.put(K_LFM_SECRET, secret.trim())
        SecureBox.put(K_LFM_SESSION, session.getString("key"))
        SecureBox.put(K_LFM_USER, session.optString("name", user))
        refreshAccounts()
        SettingsRepository.update { it.copy(scrobbleLastFm = true) }
        session.optString("name", user)
    }

    fun logoutLastFm() {
        listOf(K_LFM_SESSION, K_LFM_USER).forEach(SecureBox::remove)
        refreshAccounts()
        SettingsRepository.update { it.copy(scrobbleLastFm = false) }
    }

    private fun lastFmCall(method: String, extra: Map<String, String>): JSONObject {
        val key = SecureBox.get(K_LFM_KEY) ?: error("no key")
        val secret = SecureBox.get(K_LFM_SECRET) ?: error("no secret")
        val sk = SecureBox.get(K_LFM_SESSION) ?: error("no session")
        val params = sortedMapOf("api_key" to key, "method" to method, "sk" to sk)
        params.putAll(extra)
        params["api_sig"] = signature(params, secret)
        params["format"] = "json"
        val json = post(LASTFM_API, params)
        if (json.has("error")) {
            if (json.optInt("error") == 9) logoutLastFm() // invalid session
            error(json.optString("message"))
        }
        return json
    }

    /** Last.fm's api_sig: md5 of every parameter (sorted by name, `format` excluded) followed by the secret. */
    fun signature(params: Map<String, String>, secret: String): String {
        val raw = params.toSortedMap().filterKeys { it != "format" && it != "callback" }
            .entries.joinToString("") { it.key + it.value } + secret
        return MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun post(url: String, params: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).post(body).header("User-Agent", Http.USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { error("HTTP ${r.code}") }
        }
    }

    // ── ListenBrainz ────────────────────────────────────────────────────

    suspend fun loginListenBrainz(token: String): Result<String> = runCatching {
        val req = Request.Builder().url(LB_API + "validate-token")
            .header("Authorization", "Token ${token.trim()}").header("User-Agent", Http.USER_AGENT).build()
        val json = client.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty()) }
        if (!json.optBoolean("valid")) error(json.optString("message").ifBlank { "Invalid token" })
        val user = json.optString("user_name")
        SecureBox.put(K_LB_TOKEN, token.trim())
        SecureBox.put(K_LB_USER, user)
        refreshAccounts()
        SettingsRepository.update { it.copy(scrobbleListenBrainz = true) }
        user
    }

    fun logoutListenBrainz() {
        listOf(K_LB_TOKEN, K_LB_USER).forEach(SecureBox::remove)
        refreshAccounts()
        SettingsRepository.update { it.copy(scrobbleListenBrainz = false) }
    }

    fun lbListen(listenedAt: Long?, artist: String, title: String, album: String?, durationSec: Long): JSONObject =
        JSONObject().apply {
            if (listenedAt != null) put("listened_at", listenedAt)
            put("track_metadata", JSONObject().apply {
                put("artist_name", artist)
                put("track_name", title)
                album?.let { put("release_name", it) }
                put("additional_info", JSONObject().apply {
                    if (durationSec > 0) put("duration_ms", durationSec * 1000)
                    put("media_player", "VYORA")
                    put("submission_client", "VYORA")
                    put("submission_client_version", BuildConfig.VERSION_NAME)
                    put("music_service", "youtube.com")
                })
            })
        }

    private fun lbSubmit(type: String, listens: List<JSONObject>) {
        val token = SecureBox.get(K_LB_TOKEN) ?: error("no token")
        val payload = JSONObject().put("listen_type", type).put("payload", JSONArray(listens))
        val req = Request.Builder().url(LB_API + "submit-listens")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Token $token").header("User-Agent", Http.USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) logoutListenBrainz()
            if (!r.isSuccessful) error("HTTP ${r.code}")
        }
    }
}
