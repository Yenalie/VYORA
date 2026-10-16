package com.vyora.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE item_id = :id")
    suspend fun lyrics(id: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLyrics(entry: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache")
    suspend fun clearLyrics()

    @Query("DELETE FROM lyrics_cache WHERE item_id NOT IN (SELECT item_id FROM lyrics_cache ORDER BY fetched_at DESC LIMIT :keep)")
    suspend fun trimLyrics(keep: Int)

    @Insert
    suspend fun addScrobble(entry: PendingScrobbleEntity)

    @Query("SELECT * FROM pending_scrobbles ORDER BY played_at ASC LIMIT :limit")
    suspend fun pendingScrobbles(limit: Int): List<PendingScrobbleEntity>

    @Query("UPDATE pending_scrobbles SET targets = :targets WHERE id = :id")
    suspend fun setScrobbleTargets(id: Long, targets: Int)

    @Query("DELETE FROM pending_scrobbles WHERE targets = 0 OR played_at < :before")
    suspend fun pruneScrobbles(before: Long)

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    suspend fun pendingScrobbleCount(): Int
}
