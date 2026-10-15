package com.vyora.music.data.library

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.vyora.music.data.account.SyncEngine
import com.vyora.music.data.db.HistoryEntity
import com.vyora.music.data.db.ItemEntity
import com.vyora.music.data.db.LegacyImport
import com.vyora.music.data.db.LikedEntity
import com.vyora.music.data.db.PlaylistEntity
import com.vyora.music.data.db.PlaylistSongEntity
import com.vyora.music.data.db.SavedEntity
import com.vyora.music.data.db.SyncActionEntity
import com.vyora.music.data.db.SyncKind
import com.vyora.music.data.db.VyoraDatabase
import com.vyora.music.data.youtube.SimpleItem
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

object LibraryManager {
    private const val TAG = "VYORA-Library"
    const val MAX_RECENT = 100

    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r ->
        Thread(r, "vyora-library").apply { isDaemon = true }
    }.asCoroutineDispatcher())

    private var db: VyoraDatabase? = null

    private val _likedSongs = MutableStateFlow<List<SimpleItem>>(emptyList())
    val likedSongs: StateFlow<List<SimpleItem>> = _likedSongs.asStateFlow()

    private val _recentSongs = MutableStateFlow<List<SimpleItem>>(emptyList())
    val recentSongs: StateFlow<List<SimpleItem>> = _recentSongs.asStateFlow()

    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _saved = MutableStateFlow<List<SimpleItem>>(emptyList())
    val savedCollections: StateFlow<List<SimpleItem>> = _saved.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (db != null) return
        val app = context.applicationContext
        val database = VyoraDatabase.get(app)
        db = database
        val dao = database.library()

        scope.launch {
            LegacyImport.runOnce(app, database)
            runCatching { dao.deleteOrphanItems() }.onFailure { Log.w(TAG, "Orphan cleanup failed", it) }
        }
        scope.launch { dao.likedFlow().collect { rows -> _likedSongs.value = rows.map(ItemEntity::toItem) } }
        scope.launch { dao.historyFlow().collect { rows -> _recentSongs.value = rows.map(ItemEntity::toItem) } }
        scope.launch { dao.savedFlow().collect { rows -> _saved.value = rows.map(ItemEntity::toItem) } }
        scope.launch {
            combine(dao.playlistsFlow(), dao.playlistSongsFlow()) { lists, songs ->
                val byPlaylist = songs.groupBy({ it.playlistId }, { it.item.toItem() })
                lists.map { p -> UserPlaylist(p.id, p.name, byPlaylist[p.id].orEmpty(), p.createdAt, p.remoteId) }
            }.collect { _playlists.value = it }
        }
    }

    fun isLiked(id: String): Boolean = _likedSongs.value.any { it.id == id }

    fun toggleLike(item: SimpleItem) {
        val wasLiked = isLiked(item.id)
        _likedSongs.value = if (wasLiked) _likedSongs.value.filterNot { it.id == item.id } else listOf(item) + _likedSongs.value
        write { database ->
            val dao = database.library()
            database.withTransaction {
                if (wasLiked) {
                    dao.deleteLiked(item.id)
                } else {
                    dao.upsertItem(ItemEntity.of(item))
                    dao.upsertLiked(LikedEntity(item.id, System.currentTimeMillis()))
                }
                queue(database, if (wasLiked) SyncKind.UNLIKE else SyncKind.LIKE, item.id, replaces = listOf(SyncKind.LIKE, SyncKind.UNLIKE))
            }
        }
    }

    fun addToRecent(item: SimpleItem) = write { database ->
        val dao = database.library()
        database.withTransaction {
            dao.upsertItem(ItemEntity.of(item))
            dao.upsertHistory(HistoryEntity(item.id, System.currentTimeMillis()))
            dao.trimHistory(MAX_RECENT)
        }
    }

    fun clearRecent() = write { it.library().clearHistory(); it.library().deleteOrphanItems() }

    fun isSaved(id: String): Boolean = _saved.value.any { it.id == id }

    fun toggleSaved(item: SimpleItem) {
        val wasSaved = isSaved(item.id)
        _saved.value = if (wasSaved) _saved.value.filterNot { it.id == item.id } else listOf(item) + _saved.value
        write { database ->
            val dao = database.library()
            database.withTransaction {
                if (wasSaved) {
                    dao.deleteSaved(item.id)
                } else {
                    dao.upsertItem(ItemEntity.of(item))
                    dao.upsertSaved(SavedEntity(item.id, System.currentTimeMillis()))
                }
                queue(database, if (wasSaved) SyncKind.UNSAVE else SyncKind.SAVE, item.id, replaces = listOf(SyncKind.SAVE, SyncKind.UNSAVE))
            }
        }
    }

    fun createPlaylist(name: String, fallbackName: String, first: SimpleItem? = null): String {
        val id = "vy_" + UUID.randomUUID().toString().take(12)
        val clean = name.trim().ifBlank { fallbackName.trim() }
        val now = System.currentTimeMillis()
        _playlists.value = listOf(UserPlaylist(id, clean, listOfNotNull(first), now)) + _playlists.value
        write { database ->
            val dao = database.library()
            database.withTransaction {
                dao.insertPlaylist(PlaylistEntity(id, clean, now))
                if (first != null) {
                    dao.upsertItem(ItemEntity.of(first))
                    dao.insertPlaylistSong(PlaylistSongEntity(id, first.id, 0))
                }
            }
            SyncEngine.kick()
        }
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().takeIf { it.isNotBlank() } ?: return
        write { database ->
            database.withTransaction {
                database.library().renamePlaylist(id, clean)
                queue(database, SyncKind.PLAYLIST_RENAME, id, extra = clean, replaces = listOf(SyncKind.PLAYLIST_RENAME))
            }
        }
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        write { database ->
            val dao = database.library()
            database.withTransaction {
                val remoteId = dao.playlist(id)?.remoteId
                dao.deletePlaylist(id)
                database.sync().deleteFor(id, listOf(SyncKind.PLAYLIST_ADD, SyncKind.PLAYLIST_REMOVE, SyncKind.PLAYLIST_RENAME))
                if (remoteId != null) queue(database, SyncKind.PLAYLIST_DELETE, remoteId)
                dao.deleteOrphanItems()
            }
        }
    }

    fun addToPlaylist(id: String, item: SimpleItem) = write { database ->
        val dao = database.library()
        database.withTransaction {
            dao.upsertItem(ItemEntity.of(item))
            val inserted = dao.insertPlaylistSong(PlaylistSongEntity(id, item.id, dao.lastPosition(id) + 1))
            if (inserted != -1L) queue(database, SyncKind.PLAYLIST_ADD, id, extra = item.id)
        }
    }

    fun removeFromPlaylist(id: String, songId: String) = write { database ->
        val dao = database.library()
        database.withTransaction {
            val handle = dao.playlistSong(id, songId)?.setVideoId
            dao.deletePlaylistSong(id, songId)
            queue(database, SyncKind.PLAYLIST_REMOVE, id, extra = removeExtra(songId, handle))
        }
    }

    internal fun removeExtra(songId: String, handle: String?) = if (handle.isNullOrBlank()) songId else "$songId|$handle"

    private suspend fun queue(database: VyoraDatabase, kind: String, target: String, extra: String? = null, replaces: List<String> = emptyList()) {
        if (!SyncEngine.isSignedIn()) return
        val sync = database.sync()
        if (replaces.isNotEmpty()) sync.deleteFor(target, replaces)
        sync.insert(SyncActionEntity(kind = kind, target = target, extra = extra))
        SyncEngine.kick()
    }

    private fun write(block: suspend (VyoraDatabase) -> Unit) {
        val database = db ?: run {
            Log.w(TAG, "LibraryManager used before init(); change dropped")
            return
        }
        scope.launch {
            try {
                block(database)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist library change", e)
            }
        }
    }
}

data class UserPlaylist(
    val id: String,
    val name: String,
    val songs: List<SimpleItem>,
    val createdAt: Long,
    val remoteId: String? = null,
) {
    val isSynced: Boolean get() = remoteId != null

    val covers: List<String> get() = songs.mapNotNull { it.thumbnail }.distinct().take(4)
}

fun primaryArtist(artist: String): String =
    artist.split(",", "&", " feat.", " ft.", " x ", " X ", "•").first().trim()
