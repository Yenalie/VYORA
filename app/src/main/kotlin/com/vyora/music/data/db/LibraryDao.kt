package com.vyora.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    // ------------------------------------------------------------------------------ items

    @Upsert
    suspend fun upsertItem(item: ItemEntity)

    @Upsert
    suspend fun upsertItems(items: List<ItemEntity>)

    /** Items nothing points to any more (unliked, dropped from history and playlists). */
    @Query(
        """DELETE FROM items WHERE id NOT IN (SELECT item_id FROM liked)
           AND id NOT IN (SELECT item_id FROM history)
           AND id NOT IN (SELECT item_id FROM saved_collections)
           AND id NOT IN (SELECT item_id FROM playlist_songs)"""
    )
    suspend fun deleteOrphanItems(): Int

    // ------------------------------------------------------------------------------ liked

    @Query("SELECT items.* FROM liked JOIN items ON items.id = liked.item_id ORDER BY liked.liked_at DESC")
    fun likedFlow(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM liked")
    suspend fun likedRows(): List<LikedEntity>

    @Query("SELECT * FROM liked WHERE item_id = :id")
    suspend fun liked(id: String): LikedEntity?

    @Upsert
    suspend fun upsertLiked(row: LikedEntity)

    @Upsert
    suspend fun upsertLiked(rows: List<LikedEntity>)

    @Query("DELETE FROM liked WHERE item_id = :id")
    suspend fun deleteLiked(id: String)

    @Query("DELETE FROM liked WHERE item_id IN (:ids)")
    suspend fun deleteLiked(ids: List<String>)

    @Query("UPDATE liked SET synced = :synced WHERE item_id = :id")
    suspend fun setLikedSynced(id: String, synced: Boolean)

    @Query("DELETE FROM liked WHERE synced = 1")
    suspend fun deleteSyncedLikes()

    @Query("UPDATE liked SET synced = 0")
    suspend fun unsyncAllLikes()

    @Query("SELECT MIN(liked_at) FROM liked")
    suspend fun oldestLike(): Long?

    // ---------------------------------------------------------------------------- history

    @Query("SELECT items.* FROM history JOIN items ON items.id = history.item_id ORDER BY history.played_at DESC")
    fun historyFlow(): Flow<List<ItemEntity>>

    @Upsert
    suspend fun upsertHistory(row: HistoryEntity)

    @Upsert
    suspend fun upsertHistory(rows: List<HistoryEntity>)

    /** Keeps the newest [keep] rows. */
    @Query("DELETE FROM history WHERE item_id NOT IN (SELECT item_id FROM history ORDER BY played_at DESC LIMIT :keep)")
    suspend fun trimHistory(keep: Int)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    // ---------------------------------------------------------------------- saved albums

    @Query("SELECT items.* FROM saved_collections JOIN items ON items.id = saved_collections.item_id ORDER BY saved_collections.saved_at DESC")
    fun savedFlow(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM saved_collections")
    suspend fun savedRows(): List<SavedEntity>

    @Query("SELECT * FROM saved_collections WHERE item_id = :id")
    suspend fun saved(id: String): SavedEntity?

    @Upsert
    suspend fun upsertSaved(row: SavedEntity)

    @Upsert
    suspend fun upsertSaved(rows: List<SavedEntity>)

    @Query("DELETE FROM saved_collections WHERE item_id = :id")
    suspend fun deleteSaved(id: String)

    @Query("DELETE FROM saved_collections WHERE item_id IN (:ids)")
    suspend fun deleteSaved(ids: List<String>)

    @Query("UPDATE saved_collections SET synced = :synced WHERE item_id = :id")
    suspend fun setSavedSynced(id: String, synced: Boolean)

    /** Saved collections of one [type] (album / playlist) that came from or went to YouTube. */
    @Query("SELECT s.item_id FROM saved_collections s JOIN items i ON i.id = s.item_id WHERE s.synced = 1 AND i.type = :type")
    suspend fun syncedSavedIds(type: String): List<String>

    @Query("SELECT s.item_id FROM saved_collections s JOIN items i ON i.id = s.item_id WHERE s.synced = 0 AND i.type = :type")
    suspend fun unsyncedSavedIds(type: String): List<String>

    @Query("DELETE FROM saved_collections WHERE synced = 1")
    suspend fun deleteSyncedSaved()

    @Query("UPDATE saved_collections SET synced = 0")
    suspend fun unsyncAllSaved()

    // ------------------------------------------------------------------------- playlists

    @Query("SELECT * FROM playlists ORDER BY created_at DESC")
    fun playlistsFlow(): Flow<List<PlaylistEntity>>

    @Query(
        """SELECT playlist_songs.playlist_id, playlist_songs.set_video_id, items.* FROM playlist_songs
           JOIN items ON items.id = playlist_songs.item_id
           ORDER BY playlist_songs.playlist_id, playlist_songs.position"""
    )
    fun playlistSongsFlow(): Flow<List<PlaylistSongRow>>

    @Query("SELECT * FROM playlists")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE remote_id = :remoteId")
    suspend fun playlistByRemote(remoteId: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(row: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String)

    @Query("UPDATE playlists SET remote_id = :remoteId WHERE id = :id")
    suspend fun setPlaylistRemote(id: String, remoteId: String?)

    @Query("UPDATE playlists SET remote_id = NULL")
    suspend fun detachAllPlaylists()

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("DELETE FROM playlists WHERE remote_id IS NOT NULL")
    suspend fun deleteRemotePlaylists()

    @Query("SELECT * FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY position")
    suspend fun playlistSongs(playlistId: String): List<PlaylistSongEntity>

    @Query("SELECT * FROM playlist_songs WHERE playlist_id = :playlistId AND item_id = :itemId")
    suspend fun playlistSong(playlistId: String, itemId: String): PlaylistSongEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun lastPosition(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSong(row: PlaylistSongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(rows: List<PlaylistSongEntity>)

    @Query("UPDATE playlist_songs SET set_video_id = :setVideoId WHERE playlist_id = :playlistId AND item_id = :itemId")
    suspend fun setPlaylistSongHandle(playlistId: String, itemId: String, setVideoId: String?)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND item_id = :itemId")
    suspend fun deletePlaylistSong(playlistId: String, itemId: String)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun clearPlaylist(playlistId: String)

    /** Replaces a playlist's songs with [songs] (a fresh copy from YouTube). */
    @Transaction
    suspend fun replacePlaylistSongs(playlistId: String, items: List<ItemEntity>, handles: List<String?>) {
        upsertItems(items)
        clearPlaylist(playlistId)
        insertPlaylistSongs(items.mapIndexed { i, item -> PlaylistSongEntity(playlistId, item.id, i, handles.getOrNull(i)) }.distinctBy { it.itemId })
    }

    // ------------------------------------------------------------------- search history

    @Query("SELECT `query` FROM search_history ORDER BY searched_at DESC")
    fun searchesFlow(): Flow<List<String>>

    @Upsert
    suspend fun upsertSearch(row: SearchEntity)

    @Upsert
    suspend fun upsertSearches(rows: List<SearchEntity>)

    @Query("DELETE FROM search_history WHERE `key` = :key")
    suspend fun deleteSearch(key: String)

    @Query("DELETE FROM search_history WHERE `key` NOT IN (SELECT `key` FROM search_history ORDER BY searched_at DESC LIMIT :keep)")
    suspend fun trimSearches(keep: Int)

    @Query("DELETE FROM search_history")
    suspend fun clearSearches()
}
