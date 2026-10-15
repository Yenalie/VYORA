package com.vyora.music.data.db

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.SimpleItemJson
import kotlinx.coroutines.flow.first
import org.json.JSONArray

private val Context.legacyLibraryStore: DataStore<Preferences> by preferencesDataStore(name = "vyora_library_v2")

internal object LegacyImport {
    private const val TAG = "VYORA-Import"
    private const val META_PREFS = "vyora_db_meta"
    private const val KEY_DONE = "legacy_imported_v1"

    private val KEY_LIKED = stringPreferencesKey("liked_songs_json")
    private val KEY_RECENT = stringPreferencesKey("recent_songs_json")
    private val KEY_PLAYLISTS = stringPreferencesKey("user_playlists_json")
    private val KEY_SAVED = stringPreferencesKey("saved_collections_json")

    suspend fun runOnce(context: Context, db: VyoraDatabase) {
        val app = context.applicationContext
        val meta = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        if (meta.getBoolean(KEY_DONE, false)) return
        try {
            val store = app.legacyLibraryStore
            val prefs = store.data.first()
            val oldPrefs = app.getSharedPreferences("vyora_library", Context.MODE_PRIVATE)
            val searchPrefs = app.getSharedPreferences("vyora_search", Context.MODE_PRIVATE)

            val liked = LegacyFormat.decodeItems(prefs[KEY_LIKED])
                .ifEmpty { LegacyFormat.decodeDelimited(oldPrefs.getString("liked_songs", null)) }
            val recent = LegacyFormat.decodeItems(prefs[KEY_RECENT])
                .ifEmpty { LegacyFormat.decodeDelimited(oldPrefs.getString("recent_songs", null)) }
            val saved = LegacyFormat.decodeItems(prefs[KEY_SAVED])
            val playlists = LegacyFormat.decodePlaylists(prefs[KEY_PLAYLISTS])
            val searches = LegacyFormat.decodeSearches(searchPrefs.getString("recent_queries", null))

            if (liked.isNotEmpty() || recent.isNotEmpty() || saved.isNotEmpty() || playlists.isNotEmpty() || searches.isNotEmpty()) {
                write(db, liked, recent, saved, playlists, searches)
                Log.i(TAG, "Imported ${liked.size} liked, ${recent.size} recent, ${saved.size} saved, ${playlists.size} playlists")
            }

            store.edit { it.clear() }
            oldPrefs.edit().clear().apply()
            searchPrefs.edit().clear().apply()
            meta.edit().putBoolean(KEY_DONE, true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Import failed; will retry next launch", e)
        }
    }

    private suspend fun write(
        db: VyoraDatabase,
        liked: List<SimpleItem>,
        recent: List<SimpleItem>,
        saved: List<SimpleItem>,
        playlists: List<LegacyFormat.Playlist>,
        searches: List<String>,
    ) {
        val dao = db.library()
        val now = System.currentTimeMillis()
        fun stamp(index: Int) = now - index * 1000L
        db.withTransaction {
            dao.upsertItems((liked + recent + saved + playlists.flatMap { it.songs }).distinctBy { it.id }.map(ItemEntity::of))
            dao.upsertLiked(liked.mapIndexed { i, it -> LikedEntity(it.id, stamp(i)) })
            dao.upsertHistory(recent.mapIndexed { i, it -> HistoryEntity(it.id, stamp(i)) })
            dao.upsertSaved(saved.mapIndexed { i, it -> SavedEntity(it.id, stamp(i)) })
            playlists.forEach { p ->
                if (dao.playlist(p.id) == null) {
                    dao.insertPlaylist(PlaylistEntity(p.id, p.name, p.createdAt.takeIf { it > 0 } ?: now))
                    dao.insertPlaylistSongs(p.songs.distinctBy { it.id }.mapIndexed { i, s -> PlaylistSongEntity(p.id, s.id, i) })
                }
            }
            dao.upsertSearches(searches.mapIndexed { i, q -> SearchEntity(q.lowercase(), q, stamp(i)) })
        }
    }
}

internal object LegacyFormat {
    data class Playlist(val id: String, val name: String, val songs: List<SimpleItem>, val createdAt: Long)

    fun decodeItems(raw: String?): List<SimpleItem> = SimpleItemJson.decode(raw)

    fun decodePlaylists(raw: String?): List<Playlist> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(Playlist(id, o.optString("name"), o.optJSONArray("songs")?.let(SimpleItemJson::decode).orEmpty(), o.optLong("created", 0L)))
            }
        }
    }

    fun decodeSearches(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    }

    fun decodeDelimited(raw: String?): List<SimpleItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|||").mapNotNull { part ->
            val p = part.split("###")
            if (p.size >= 3 && p[0].isNotBlank()) {
                SimpleItem(
                    id = p[0], title = p[1], artist = p[2],
                    thumbnail = p.getOrNull(3)?.takeIf { it.isNotBlank() },
                    url = SimpleItem.watchUrl(p[0]),
                )
            } else null
        }
    }
}
