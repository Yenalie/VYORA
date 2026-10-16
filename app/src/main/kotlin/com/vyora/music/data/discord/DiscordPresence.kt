package com.vyora.music.data.discord

import android.util.Log
import com.vyora.music.data.settings.AppSettings
import com.vyora.music.data.settings.SettingsRepository
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** What is playing, as the playback service sees it. Times are epoch milliseconds. */
data class PresenceTrack(
    val title: String,
    val artist: String,
    val startedAt: Long,
    val endsAt: Long,
    val album: String = "",
    val artworkUrl: String? = null,
    val artistArtworkUrl: String? = null,
    val youtubeId: String? = null,
    val paused: Boolean = false,
)

enum class DiscordStatus { OFF, CONNECTING, CONNECTED, AUTH_FAILED, ERROR }

/**
 * Discord Rich Presence over the gateway, ported from YumaPlayer / ArchiveTune's DiscordSocialPresenceClient
 * and DiscordRPC (GPL-3.0). VYORA identifies with the OAuth2 bearer token from [DiscordAccount] (scope
 * `sdk.social_layer_presence`) and sends op 3 presence updates carrying the song title, artist, album art
 * (registered through the application's external-assets endpoint), playback timestamps and buttons.
 */
object DiscordPresence {
    private const val TAG = "VYORA-Discord"
    private const val GATEWAY = "wss://gateway.discord.gg/?v=9&encoding=json"
    private const val API = "https://discord.com/api/v10"
    private const val SEND_DELAY_MS = 1_200L
    private const val IDLE_DISCONNECT_MS = 10 * 60_000L
    private const val GET_VYORA_URL = "https://github.com/Yenalie/VYORA"
    private val NON_RESUMABLE = setOf(4004, 4010, 4011, 4012, 4013, 4014)

    private val CAPABILITIES = (1 shl 4) or (1 shl 5) or (1 shl 12) or (1 shl 16)
    private val INTENTS = (1 shl 12) or (1 shl 18) or (1 shl 19) or (1 shl 22) or (1 shl 23) or
        (1 shl 27) or (1 shl 28) or (1 shl 29)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val assetCache = ConcurrentHashMap<String, String>()

    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var sendJob: Job? = null
    private var reconnectJob: Job? = null
    private var idleJob: Job? = null
    private var sequence: Int? = null
    private var ready = false
    private var acked = true
    private var attempts = 0
    private var token: String? = null
    private var wanted: PresenceTrack? = null
    private var lastTrack: PresenceTrack? = null
    private var sentKey: String? = null

    private val _status = MutableStateFlow(DiscordStatus.OFF)
    val status: StateFlow<DiscordStatus> = _status.asStateFlow()

    private val enabled: Boolean
        get() = SettingsRepository.current.discordPresence && DiscordAccount.user.value != null

    fun update(track: PresenceTrack?) {
        synchronized(lock) {
            lastTrack = track
            val shown = track?.takeIf { !it.paused || SettingsRepository.current.discordShowPaused }
            wanted = shown
            if (!enabled) {
                disconnectLocked()
                return
            }
            if (shown != null) {
                idleJob?.cancel()
                idleJob = null
                if (socket == null && connectJob?.isActive != true) connectLocked()
            } else {
                if (socket == null) return
                scheduleIdleDisconnectLocked()
            }
            scheduleSendLocked()
        }
    }

    /** Re-sends the last reported track, e.g. after sign-in or a Discord setting changed. */
    fun refresh() {
        synchronized(lock) { sentKey = null }
        update(lastTrack)
    }

    fun stop() {
        synchronized(lock) {
            wanted = null
            disconnectLocked()
        }
    }

    private fun connectLocked() {
        reconnectJob?.cancel()
        reconnectJob = null
        _status.value = DiscordStatus.CONNECTING
        connectJob = scope.launch {
            val access = DiscordAccount.accessToken()
            synchronized(lock) {
                connectJob = null
                if (!enabled) return@launch
                if (access == null) {
                    _status.value = DiscordStatus.AUTH_FAILED
                    return@launch
                }
                token = access
                ready = false
                sequence = null
                sentKey = null
                val request = Request.Builder().url(GATEWAY).header("User-Agent", "Discord Embedded/1.9.15780").build()
                socket = DiscordAccount.client.newWebSocket(request, Listener("Bearer $access"))
            }
        }
    }

    private fun disconnectLocked() {
        connectJob?.cancel()
        heartbeatJob?.cancel()
        sendJob?.cancel()
        reconnectJob?.cancel()
        idleJob?.cancel()
        connectJob = null
        heartbeatJob = null
        sendJob = null
        reconnectJob = null
        idleJob = null
        socket?.close(1000, null)
        socket = null
        ready = false
        sentKey = null
        attempts = 0
        if (_status.value != DiscordStatus.AUTH_FAILED || !enabled) _status.value = DiscordStatus.OFF
    }

    private fun scheduleIdleDisconnectLocked() {
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            delay(IDLE_DISCONNECT_MS)
            synchronized(lock) { if (wanted == null) disconnectLocked() }
        }
    }

    private fun scheduleSendLocked() {
        sendJob?.cancel()
        sendJob = scope.launch {
            delay(SEND_DELAY_MS)
            sendPresence()
        }
    }

    private suspend fun sendPresence() {
        val (track, access) = synchronized(lock) {
            if (socket == null || !ready) return
            wanted to token
        }
        val settings = SettingsRepository.current
        val key = track?.let { "$it|${settings.discordButtons}|${settings.discordStatus}|${settings.discordActivity}" } ?: "none"
        if (key == synchronized(lock) { sentKey }) return
        val activities = JSONArray()
        if (track != null) {
            val large = access?.let { resolveImage(it, track.artworkUrl) }
            val small = access?.let { resolveImage(it, track.artistArtworkUrl) }
            activities.put(activity(track, settings, DiscordAccount.applicationId, large, small))
        }
        val payload = JSONObject().put("op", 3).put(
            "d",
            JSONObject()
                .put("since", JSONObject.NULL)
                .put("activities", activities)
                .put("status", settings.discordStatus.wire)
                .put("afk", false),
        )
        synchronized(lock) {
            val ws = socket ?: return
            if (!ready) return
            if (ws.send(payload.toString())) sentKey = key
        }
    }

    /** Builds the activity exactly like Yuma's DiscordRPC: title / artist, cover art, timestamps, buttons. */
    internal fun activity(
        track: PresenceTrack,
        settings: AppSettings = AppSettings(),
        applicationId: String = DiscordAccount.applicationId,
        largeImage: String? = null,
        smallImage: String? = null,
    ): JSONObject {
        val json = JSONObject()
            .put("name", "VYORA")
            .put("type", settings.discordActivity.wire)
            .put("application_id", applicationId)
            .put("details", fit(track.title))
            .put("state", fit(track.artist.ifBlank { "VYORA" }))
            .put("platform", "android")
        val timestamps = JSONObject()
        if (!track.paused) {
            timestamps.put("start", track.startedAt)
            if (track.endsAt > track.startedAt) timestamps.put("end", track.endsAt)
        }
        if (timestamps.length() > 0) json.put("timestamps", timestamps)

        val assets = JSONObject()
        largeImage?.let { assets.put("large_image", it) }
        assets.put("large_text", fit(track.album.ifBlank { track.title }))
        if (track.paused) {
            assets.put("small_text", "Paused")
            (smallImage ?: largeImage)?.let { assets.put("small_image", it) }
        } else if (smallImage != null) {
            assets.put("small_image", smallImage)
            assets.put("small_text", fit("${track.artist.ifBlank { "VYORA" }} on VYORA"))
        }
        json.put("assets", assets)

        if (settings.discordButtons) {
            val labels = JSONArray()
            val urls = JSONArray()
            track.youtubeId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let {
                labels.put("Listen on YouTube Music")
                urls.put("https://music.youtube.com/watch?v=$it")
            }
            labels.put("Get VYORA")
            urls.put(GET_VYORA_URL)
            json.put("buttons", labels)
            json.put("metadata", JSONObject().put("button_urls", urls))
        }
        return json
    }

    private fun fit(text: String): String = text.trim().take(128).padEnd(2, ' ')

    /** Turns an artwork URL into something the gateway accepts (`mp:` asset path), with a cache. */
    private fun resolveImage(access: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        directImage(url)?.let { return it }
        if (!url.startsWith("http")) return null
        assetCache[url]?.let { return "mp:$it" }
        return runCatching {
            val body = JSONObject().put("urls", JSONArray().put(url)).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$API/applications/${DiscordAccount.applicationId}/external-assets")
                .header("Authorization", "Bearer $access")
                .post(body)
                .build()
            DiscordAccount.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val arr = JSONArray(response.body?.string().orEmpty())
                arr.optJSONObject(0)?.optString("external_asset_path")?.takeIf { it.isNotBlank() }
            }
        }.onFailure { Log.w(TAG, "external-assets failed", it) }
            .getOrNull()
            ?.also { assetCache[url] = it }
            ?.let { "mp:$it" }
    }

    /** Images that need no registration: snowflakes, mp: paths and Discord's own CDN. */
    internal fun directImage(image: String): String? {
        if (image.matches(Regex("[0-9]{17,20}"))) return image
        if (listOf("mp:", "youtube:", "spotify:").any { image.startsWith(it) }) return image
        if (image.startsWith("external/")) return "mp:$image"
        val host = runCatching { URI(image).host }.getOrNull() ?: return null
        if (host == "cdn.discordapp.com" || host == "media.discordapp.net") {
            return "mp:" + image.substringAfter("$host/")
        }
        return null
    }

    private fun scheduleReconnectLocked() {
        socket = null
        ready = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (!enabled || wanted == null) {
            _status.value = DiscordStatus.OFF
            return
        }
        if (reconnectJob?.isActive == true) return
        _status.value = DiscordStatus.ERROR
        val wait = (2_000L shl attempts.coerceAtMost(5)).coerceAtMost(60_000L)
        attempts++
        reconnectJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                reconnectJob = null
                if (socket == null && enabled && wanted != null) connectLocked()
            }
        }
    }

    private class Listener(private val bearer: String) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            var sendNow = false
            synchronized(lock) {
                if (webSocket !== socket) return
                if (!json.isNull("s")) sequence = json.optInt("s")
                when (json.optInt("op", -1)) {
                    10 -> {
                        val interval = json.optJSONObject("d")?.optLong("heartbeat_interval") ?: 41_250L
                        startHeartbeatLocked(webSocket, interval)
                        webSocket.send(identify(bearer).toString())
                    }
                    11 -> acked = true
                    1 -> webSocket.send(heartbeat().toString())
                    0 -> if (json.optString("t") == "READY") {
                        ready = true
                        attempts = 0
                        sentKey = null
                        _status.value = DiscordStatus.CONNECTED
                        sendNow = true
                    }
                    7, 9 -> {
                        webSocket.close(4000, null)
                        scheduleReconnectLocked()
                    }
                }
            }
            if (sendNow) scope.launch { sendPresence() }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            closed(webSocket, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Gateway failure", t)
            closed(webSocket, -1)
        }

        private fun closed(webSocket: WebSocket, code: Int) {
            synchronized(lock) {
                if (webSocket !== socket) return
                if (code in NON_RESUMABLE) {
                    socket = null
                    ready = false
                    heartbeatJob?.cancel()
                    if (code == 4004) DiscordAccount.invalidate()
                    _status.value = DiscordStatus.AUTH_FAILED
                    return
                }
                scheduleReconnectLocked()
            }
        }
    }

    private fun startHeartbeatLocked(webSocket: WebSocket, interval: Long) {
        heartbeatJob?.cancel()
        acked = true
        heartbeatJob = scope.launch {
            delay((interval * Math.random()).toLong())
            while (isActive) {
                synchronized(lock) {
                    if (webSocket !== socket) return@launch
                    if (!acked) {
                        webSocket.cancel()
                        scheduleReconnectLocked()
                        return@launch
                    }
                    acked = false
                    webSocket.send(heartbeat().toString())
                }
                delay(interval)
            }
        }
    }

    private fun heartbeat(): JSONObject = JSONObject().put("op", 1).put("d", sequence ?: JSONObject.NULL)

    internal fun identify(bearer: String): JSONObject = JSONObject()
        .put("op", 2)
        .put(
            "d",
            JSONObject()
                .put("token", bearer)
                .put("capabilities", CAPABILITIES)
                .put("intents", INTENTS)
                .put(
                    "properties",
                    JSONObject()
                        .put("os", "Android")
                        .put("browser", "VYORA")
                        .put("device", "Android")
                        .put("browser_user_agent", "VYORA")
                        .put("browser_version", "1.0")
                        .put("client_version", "1.0")
                        .put("client_build_number", 1)
                        .put("native_build_number", 1)
                        .put("release_channel", "unknown"),
                ),
        )
}
