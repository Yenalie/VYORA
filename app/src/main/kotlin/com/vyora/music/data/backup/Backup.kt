package com.vyora.music.data.backup

import android.content.Context
import android.net.Uri
import com.vyora.music.BuildConfig
import com.vyora.music.data.db.PlayEventEntity
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.SimpleItemJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup and restore to a single JSON file the user picks (Storage Access Framework): settings, favourites,
 * saved albums/playlists, your own playlists and the listening history behind Rekap. Secrets (account cookies,
 * scrobbling tokens, server passwords) are deliberately left out.
 */
object Backup {
    const val FORMAT = 1
    const val MIME = "application/json"

    data class Contents(
        val settings: Map<String, Any?>,
        val liked: List<SimpleItem>,
        val saved: List<SimpleItem>,
        val playlists: List<Pair<String, List<SimpleItem>>>,
        val events: List<PlayEventEntity>,
    )

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "vyora-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date(now)) + ".json"

    suspend fun collect(): Contents = Contents(
        settings = SettingsRepository.exportRaw(),
        liked = LibraryManager.likedSongs.value,
        saved = LibraryManager.savedCollections.value,
        playlists = LibraryManager.playlists.value.map { it.name to it.songs },
        events = LibraryManager.allPlayEvents(),
    )

    fun encode(c: Contents): String = JSONObject()
        .put("app", "VYORA")
        .put("format", FORMAT)
        .put("version", BuildConfig.VERSION_NAME)
        .put("created_at", System.currentTimeMillis())
        .put("settings", JSONArray().apply {
            c.settings.forEach { (k, v) ->
                val type = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> null }
                if (type != null) put(JSONObject().put("k", k).put("t", type).put("v", v))
            }
        })
        .put("liked", JSONArray().apply { c.liked.forEach { put(SimpleItemJson.toJson(it)) } })
        .put("saved", JSONArray().apply { c.saved.forEach { put(SimpleItemJson.toJson(it)) } })
        .put("playlists", JSONArray().apply {
            c.playlists.forEach { (name, songs) ->
                put(JSONObject().put("name", name).put("songs", JSONArray().apply { songs.forEach { put(SimpleItemJson.toJson(it)) } }))
            }
        })
        .put("events", JSONArray().apply {
            c.events.forEach { e ->
                put(JSONArray().put(e.itemId).put(e.title).put(e.artist).put(e.thumbnail ?: JSONObject.NULL).put(e.playedAt).put(e.listenedMs))
            }
        })
        .toString()

    fun decode(raw: String): Contents {
        val o = JSONObject(raw)
        require(o.optString("app") == "VYORA") { "Not a VYORA backup" }
        require(o.optInt("format", 0) in 1..FORMAT) { "Backup from a newer VYORA" }
        val settings = buildMap<String, Any?> {
            val arr = o.optJSONArray("settings") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val k = e.getString("k")
                put(k, when (e.getString("t")) {
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v").toFloat()
                    else -> e.getString("v")
                })
            }
        }
        val playlists = o.optJSONArray("playlists")?.let { arr ->
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                p.optString("name") to SimpleItemJson.decode(p.optJSONArray("songs") ?: JSONArray())
            }
        }.orEmpty()
        val events = o.optJSONArray("events")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                PlayEventEntity(
                    itemId = e.getString(0), title = e.optString(1), artist = e.optString(2),
                    thumbnail = if (e.isNull(3)) null else e.optString(3),
                    playedAt = e.getLong(4), listenedMs = e.getLong(5),
                )
            }
        }.orEmpty()
        return Contents(
            settings = settings,
            liked = SimpleItemJson.decode(o.optJSONArray("liked") ?: JSONArray()),
            saved = SimpleItemJson.decode(o.optJSONArray("saved") ?: JSONArray()),
            playlists = playlists,
            events = events,
        )
    }

    suspend fun export(context: Context, uri: Uri): Result<Int> = runCatching {
        val c = collect()
        val json = encode(c)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("Cannot write")
        }
        c.liked.size + c.saved.size + c.playlists.size
    }

    /** Restores a backup. Settings are replaced; library content is merged. Returns how many things were added. */
    suspend fun restore(context: Context, uri: Uri): Result<Int> = runCatching {
        val raw = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Cannot read")
        }
        val c = decode(raw)
        if (c.settings.isNotEmpty()) SettingsRepository.importRaw(c.settings)
        LibraryManager.restore(c.liked, c.saved, c.playlists, c.events)
    }
}
