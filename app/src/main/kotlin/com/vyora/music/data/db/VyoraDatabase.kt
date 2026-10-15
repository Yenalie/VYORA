package com.vyora.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ItemEntity::class,
        LikedEntity::class,
        HistoryEntity::class,
        SavedEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SearchEntity::class,
        QueueEntity::class,
        QueueStateEntity::class,
        SyncActionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VyoraDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun queue(): QueueDao
    abstract fun sync(): SyncDao

    companion object {
        const val NAME = "vyora.db"

        @Volatile
        private var instance: VyoraDatabase? = null

        fun get(context: Context): VyoraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, VyoraDatabase::class.java, NAME)
                    .build()
                    .also { instance = it }
            }

        internal fun replaceForTest(db: VyoraDatabase?) {
            synchronized(this) { instance = db }
        }
    }
}
