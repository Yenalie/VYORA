package com.vyora.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert
    suspend fun insert(action: SyncActionEntity): Long

    @Query("SELECT * FROM sync_actions ORDER BY id")
    suspend fun all(): List<SyncActionEntity>

    @Query("SELECT COUNT(*) FROM sync_actions")
    fun pendingCount(): Flow<Int>

    @Query("SELECT DISTINCT target FROM sync_actions")
    suspend fun pendingTargets(): List<String>

    @Query("DELETE FROM sync_actions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sync_actions SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: Long)

    @Query("DELETE FROM sync_actions WHERE target = :target AND kind IN (:kinds)")
    suspend fun deleteFor(target: String, kinds: List<String>): Int

    @Query("DELETE FROM sync_actions")
    suspend fun clear()
}
