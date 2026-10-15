package com.vyora.music.data.discord

import android.util.Log
import com.vyora.music.data.settings.SettingsRepository
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

data class PresenceTrack(
    val title: String,
    val artist: String,
    val startedAt: Long,
    val endsAt: Long,
)

enum class DiscordStatus { OFF, CONNECTING, CONNECTED, AUTH_FAILED, ERROR }

object DiscordPresence {
    private const val TAG = "VYORA-Discord"
    private const val GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
    private const val SEND_DELAY_MS = 1_500L
    private const val IDLE_DISCONNECT_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var sendJob: Job? = null
    private var reconnectJob: Job? = null
    private var idleJob: Job? = null
    private var sequence: Int? = null
    private var ready = false
    private var acked = true
    private var attempts = 0
    private var wanted: PresenceTrack? = null
    private var sent: PresenceTrack? = null
    private var sentAnything = false

    private val _status = MutableStateFlow(DiscordStatus.OFF)
    val status: StateFlow<DiscordStatus> = _status.asStateFlow()

    private val enabled: Boolean
        get() = SettingsRepository.current.discordPresence && DiscordAccount.user.value != null

    fun update(track: PresenceTrack?) {
        synchronized(lock) {
            wanted = track
            if (!enabled) {
                disconnectLocked()
                return
            }
            if (track != null) {
                idleJob?.cancel()
                idleJob = null
                if (socket == null) connectLocked()
            } else {
                if (socket == null) return
                scheduleIdleDisconnectLocked()
            }
            scheduleSendLocked()
        }
    }

    fun refresh() = update(synchronized(lock) { wanted })

    fun stop() {
        synchronized(lock) {
            wanted = null
            disconnectLocked()
        }
    }

    private fun connectLocked() {
        val token = DiscordAccount.token()
        if (token == null) {
            _status.value = DiscordStatus.AUTH_FAILED
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        ready = false
        sequence = null
        sentAnything = false
        sent = null
        _status.value = DiscordStatus.CONNECTING
        val request = Request.Builder().url(GATEWAY).build()
        socket = DiscordAccount.client.newWebSocket(request, Listener(token))
    }

    private fun disconnectLocked() {
        heartbeatJob?.cancel()
        sendJob?.cancel()
        reconnectJob?.cancel()
        idleJob?.cancel()
        heartbeatJob = null
        sendJob = null
        reconnectJob = null
        idleJob = null
        socket?.close(1000, null)
        socket = null
        ready = false
        sent = null
        sentAnything = false
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
            synchronized(lock) { sendPresenceLocked() }
        }
    }

    private fun sendPresenceLocked() {
        val ws = socket ?: return
        if (!ready) return
        val track = wanted
        if (sentAnything && track == sent) return
        val activities = JSONArray()
        if (track != null) activities.put(activity(track))
        val payload = JSONObject()
            .put("op", 3)
            .put(
                "d",
                JSONObject()
                    .put("since", 0)
                    .put("activities", activities)
                    .put("status", "online")
                    .put("afk", false),
            )
        if (ws.send(payload.toString())) {
            sent = track
            sentAnything = true
        }
    }

    internal fun activity(track: PresenceTrack): JSONObject {
        val timestamps = JSONObject().put("start", track.startedAt)
        if (track.endsAt > track.startedAt) timestamps.put("end", track.endsAt)
        return JSONObject()
            .put("name", "VYORA")
            .put("type", 2)
            .put("details", fit(track.title))
            .put("state", fit(track.artist.ifBlank { "VYORA" }))
            .put("timestamps", timestamps)
    }

    private fun fit(text: String): String = text.trim().take(128).padEnd(2, ' ')

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

    private class Listener(private val token: String) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            synchronized(lock) {
                if (webSocket !== socket) return
                if (!json.isNull("s")) sequence = json.optInt("s")
                when (json.optInt("op", -1)) {
                    10 -> {
                        val interval = json.optJSONObject("d")?.optLong("heartbeat_interval") ?: 41_250L
                        startHeartbeatLocked(webSocket, interval)
                        webSocket.send(identify(token).toString())
                    }
                    11 -> acked = true
                    1 -> webSocket.send(heartbeat().toString())
                    0 -> if (json.optString("t") == "READY") {
                        ready = true
                        attempts = 0
                        _status.value = DiscordStatus.CONNECTED
                        sendPresenceLocked()
                    }
                    7, 9 -> {
                        webSocket.close(4000, null)
                        scheduleReconnectLocked()
                    }
                }
            }
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
                if (code == 4004) {
                    socket = null
                    ready = false
                    heartbeatJob?.cancel()
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

    private fun identify(token: String): JSONObject = JSONObject()
        .put("op", 2)
        .put(
            "d",
            JSONObject()
                .put("token", token)
                .put("capabilities", 65)
                .put("compress", false)
                .put(
                    "properties",
                    JSONObject()
                        .put("os", "Android")
                        .put("browser", "Discord Android")
                        .put("device", "VYORA"),
                )
                .put(
                    "presence",
                    JSONObject()
                        .put("since", 0)
                        .put("activities", JSONArray())
                        .put("status", "online")
                        .put("afk", false),
                ),
        )
}
