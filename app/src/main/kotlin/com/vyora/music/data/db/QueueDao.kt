package com.vyora.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY position")
    fun items(): List<QueueEntity>

    @Query("SELECT * FROM queue_state WHERE id = 0")
    fun state(): QueueStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItems(rows: List<QueueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertState(row: QueueStateEntity)

    @Query("DELETE FROM queue")
    fun clearItems()

    @Query("DELETE FROM queue_state")
    fun clearState()

    @Transaction
    fun replace(rows: List<QueueEntity>, state: QueueStateEntity) {
        clearItems()
        insertItems(rows)
        insertState(state)
    }

    @Transaction
    fun clear() {
        clearItems()
        clearState()
    }
}
