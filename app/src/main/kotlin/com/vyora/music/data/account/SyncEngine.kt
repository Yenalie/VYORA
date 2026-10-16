package com.vyora.music.data.account

import com.vyora.music.data.youtube.SimpleItem
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.vyora.music.data.db.ItemEntity
import com.vyora.music.data.db.LikedEntity
import com.vyora.music.data.db.PlaylistEntity
import com.vyora.music.data.db.SavedEntity
import com.vyora.music.data.db.SyncActionEntity
import com.vyora.music.data.db.SyncKind
import com.vyora.music.data.db.VyoraDatabase
import com.vyora.music.data.youtube.ItemType
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val error: SyncError? = null,
)

enum class SyncError { OFFLINE, SESSION_EXPIRED, FAILED }

object SyncEngine {
    private const val TAG = "VYORA-Sync"
    private const val PREFS = "vyora_sync"
    private const val KEY_LAST = "last_sync"
    private const val AUTO_SYNC_AFTER_MS = 30 * 60 * 1000L
    private const val PUSH_DEBOUNCE_MS = 800L
    private const val MAX_ATTEMPTS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var db: VyoraDatabase? = null
    private var appContext: Context? = null
    private var pushJob: Job? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun isSignedIn(): Boolean = AccountStore.isSignedIn

    @Synchronized
    fun init(context: Context) {
        if (db != null) return
        val app = context.applicationContext
        appContext = app
        db = VyoraDatabase.get(app)
        AccountStore.init(app)
        val last = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
        _status.value = SyncStatus(lastSyncAt = last)
        if (isSignedIn() && System.currentTimeMillis() - last > AUTO_SYNC_AFTER_MS) syncNow() else kick()
    }

    suspend fun signIn(cookie: String): Result<YouTubeAccount> = runCatching {
        require(InnerTubeAuth.hasSession(cookie)) { "No YouTube session in cookies" }
        val account = YouTubeMusicApi.account(cookie) ?: throw SessionExpiredException("No account on this session")
        AccountStore.save(cookie, account)
        _status.update { it.copy(error = null) }
        syncNow()
        account
    }

    fun signOut(keepLibrary: Boolean) {
        AccountStore.clear()
        pushJob?.cancel()
        scope.launch {
            mutex.withLock {
                val database = db ?: return@withLock
                val dao = database.library()
                database.withTransaction {
                    database.sync().clear()
                    if (keepLibrary) {
                        dao.unsyncAllLikes()
                        dao.unsyncAllSaved()
                        dao.detachAllPlaylists()
                    } else {
                        dao.deleteSyncedLikes()
                        dao.deleteSyncedSaved()
                        dao.deleteRemotePlaylists()
                    }
                    dao.deleteOrphanItems()
                }
            }
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
            _status.value = SyncStatus()
        }
    }

    fun kick() {
        if (!isSignedIn()) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            mutex.withLock { guarded { push(); uploadLocalPlaylists() } }
        }
    }

    fun syncNow() {
        if (!isSignedIn()) return
        scope.launch {
            mutex.withLock {
                _status.update { it.copy(running = true) }
                val ok = guarded {
                    push()
                    pullLikes()
                    pullPlaylists()
                    pullAlbums()
                    uploadLocalPlaylists()
                    push()
                }
                val now = System.currentTimeMillis()
                if (ok) appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putLong(KEY_LAST, now)?.apply()
                _status.update { it.copy(running = false, lastSyncAt = if (ok) now else it.lastSyncAt) }
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit): Boolean = try {
        block()
        _status.update { it.copy(error = null) }
        true
    } catch (e: SessionExpiredException) {
        Log.w(TAG, "Session refused", e)
        _status.update { it.copy(error = SyncError.SESSION_EXPIRED) }
        false
    } catch (e: IOException) {
        Log.w(TAG, "Sync paused (network)", e)
        _status.update { it.copy(error = SyncError.OFFLINE) }
        false
    } catch (e: Exception) {
        Log.e(TAG, "Sync failed", e)
        _status.update { it.copy(error = SyncError.FAILED) }
        false
    }

    private suspend fun push() {
        val database = db ?: return
        val sync = database.sync()
        for (action in sync.all()) {
            if (!isSignedIn()) return
            try {
                send(database, action)
                sync.delete(action.id)
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: IOException) {
                if (action.attempts + 1 >= MAX_ATTEMPTS) sync.delete(action.id) else sync.bumpAttempts(action.id)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Dropping change ${action.kind} ${action.target}", e)
                sync.delete(action.id)
            }
        }
    }

    private suspend fun send(database: VyoraDatabase, action: SyncActionEntity) {
        val dao = database.library()
        when (action.kind) {
            SyncKind.LIKE -> {
                YouTubeMusicApi.like(action.target)
                dao.setLikedSynced(action.target, true)
            }
            SyncKind.UNLIKE -> YouTubeMusicApi.unlike(action.target)
            SyncKind.SAVE -> {
                YouTubeMusicApi.savePlaylist(action.target)
                dao.setSavedSynced(action.target, true)
            }
            SyncKind.UNSAVE -> YouTubeMusicApi.unsavePlaylist(action.target)
            SyncKind.PLAYLIST_DELETE -> YouTubeMusicApi.deletePlaylist(action.target)
            SyncKind.PLAYLIST_RENAME -> {
                val remote = dao.playlist(action.target)?.remoteId ?: return
                YouTubeMusicApi.renamePlaylist(remote, action.extra ?: return)
            }
            SyncKind.PLAYLIST_ADD -> {
                val remote = dao.playlist(action.target)?.remoteId ?: return
                val videoId = action.extra ?: return
                val row = dao.playlistSong(action.target, videoId) ?: return
                if (row.setVideoId != null) return
                YouTubeMusicApi.addToPlaylist(remote, videoId)
            }
            SyncKind.PLAYLIST_REMOVE -> {
                val remote = dao.playlist(action.target)?.remoteId ?: return
                val extra = action.extra ?: return
                val videoId = extra.substringBefore('|')
                val handle = extra.substringAfter('|', "").ifBlank {
                    YouTubeMusicApi.playlist(remote).songs.items.firstOrNull { it.item.id == videoId }?.setVideoId
                } ?: return
                YouTubeMusicApi.removeFromPlaylist(remote, videoId, handle)
            }
        }
    }

    private suspend fun pullLikes() {
        val database = db ?: return
        val dao = database.library()
        val remote = YouTubeMusicApi.likedSongs()
        val pending = database.sync().pendingTargets().toSet()
        val remoteIds = remote.items.map { it.item.id }.toSet()
        val now = System.currentTimeMillis()
        database.withTransaction {
            val local = dao.likedRows().associateBy { it.itemId }
            dao.upsertItems(remote.items.map { ItemEntity.of(it.item) })
            val added = remote.items.filter { it.item.id !in local && it.item.id !in pending }
            dao.upsertLiked(added.mapIndexed { i, s -> LikedEntity(s.item.id, now - i * 1000L, synced = true) })
            local.values.filter { it.itemId in remoteIds && !it.synced }.forEach { dao.setLikedSynced(it.itemId, true) }
            val gone = local.values.filter { it.synced && it.itemId !in remoteIds && it.itemId !in pending }
            if (remote.complete && gone.isNotEmpty()) dao.deleteLiked(gone.map { it.itemId })
            local.values.filter { !it.synced && it.itemId !in remoteIds && it.itemId !in pending }.forEach {
                database.sync().insert(SyncActionEntity(kind = SyncKind.LIKE, target = it.itemId))
            }
        }
    }

    private suspend fun pullPlaylists() {
        val database = db ?: return
        val dao = database.library()
        val library = YouTubeMusicApi.libraryPlaylists()
        val pending = database.sync().pendingTargets().toSet()
        val owned = HashSet<String>()
        val savedRemote = HashSet<String>()
        val now = System.currentTimeMillis()

        library.items.forEachIndexed { index, collection ->
            val remoteId = collection.item.id
            val content = YouTubeMusicApi.playlist(remoteId, onlyIfEditable = true)
            if (!content.editable) {
                savedRemote += remoteId
                if (remoteId !in pending) {
                    dao.upsertItem(ItemEntity.of(collection.item))
                    if (dao.saved(remoteId) == null) dao.upsertSaved(SavedEntity(remoteId, now - index * 1000L, synced = true))
                    else dao.setSavedSynced(remoteId, true)
                }
                return@forEachIndexed
            }
            owned += remoteId
            val name = content.title ?: collection.item.title
            val local = dao.playlistByRemote(remoteId)
                ?: dao.playlists().firstOrNull { it.remoteId == null && it.name.equals(name, ignoreCase = true) }
                    ?.also { dao.setPlaylistRemote(it.id, remoteId) }
                ?: PlaylistEntity(LOCAL_PREFIX + remoteId, name, now - index * 1000L, remoteId).also { dao.insertPlaylist(it) }
            if (local.id in pending) return@forEachIndexed
            if (local.name != name) dao.renamePlaylist(local.id, name)
            if (content.songs.complete || content.songs.items.isNotEmpty()) {
                dao.replacePlaylistSongs(local.id, content.songs.items.map { ItemEntity.of(it.item) }, content.songs.items.map { it.setVideoId })
            }
        }

        if (library.complete) {
            dao.playlists()
                .filter { it.remoteId != null && it.remoteId !in owned && it.id !in pending }
                .forEach { dao.deletePlaylist(it.id) }
            val goneSaved = dao.syncedSavedIds(ItemType.PLAYLIST).filter { it !in savedRemote && it !in pending }
            if (goneSaved.isNotEmpty()) dao.deleteSaved(goneSaved)
        }
        queueUnsyncedSaved(database, ItemType.PLAYLIST, savedRemote)
    }

    private suspend fun pullAlbums() {
        val database = db ?: return
        val dao = database.library()
        val remote = YouTubeMusicApi.libraryAlbums()
        val pending = database.sync().pendingTargets().toSet()
        val remoteIds = remote.items.map { it.item.id }.toSet()
        val now = System.currentTimeMillis()
        database.withTransaction {
            remote.items.forEachIndexed { i, album ->
                if (album.item.id in pending) return@forEachIndexed
                dao.upsertItem(ItemEntity.of(album.item))
                if (dao.saved(album.item.id) == null) dao.upsertSaved(SavedEntity(album.item.id, now - i * 1000L, synced = true))
                else dao.setSavedSynced(album.item.id, true)
            }
            if (remote.complete) {
                val gone = dao.syncedSavedIds(ItemType.ALBUM).filter { it !in remoteIds && it !in pending }
                if (gone.isNotEmpty()) dao.deleteSaved(gone)
            }
        }
        queueUnsyncedSaved(database, ItemType.ALBUM, remoteIds)
    }

    private suspend fun queueUnsyncedSaved(database: VyoraDatabase, type: String, remoteIds: Set<String>) {
        val pending = database.sync().pendingTargets().toSet()
        database.library().unsyncedSavedIds(type)
            .filter { it !in remoteIds && it !in pending }
            .forEach { database.sync().insert(SyncActionEntity(kind = SyncKind.SAVE, target = it)) }
    }

    private suspend fun uploadLocalPlaylists() {
        val database = db ?: return
        val dao = database.library()
        for (playlist in dao.playlists().filter { it.remoteId == null }) {
            if (!isSignedIn()) return
            val songs = dao.playlistSongs(playlist.id).map { it.itemId }.filter(SimpleItem::isYouTubeId)
            val remoteId = YouTubeMusicApi.createPlaylist(playlist.name, songs)
            dao.setPlaylistRemote(playlist.id, remoteId)
            runCatching { YouTubeMusicApi.playlist(remoteId) }.getOrNull()?.songs?.items?.forEach {
                dao.setPlaylistSongHandle(playlist.id, it.item.id, it.setVideoId)
            }
        }
    }

    private const val LOCAL_PREFIX = "yt_"
}
