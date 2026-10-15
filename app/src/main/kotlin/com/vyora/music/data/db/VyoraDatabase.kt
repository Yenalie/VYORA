package com.vyora.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's one database: library, playlists, history, searches, the saved queue and the
 * list of changes waiting to be synced to YouTube. Settings stay in SharedPreferences and the
 * YouTube sign-in in encrypted storage ([com.vyora.music.data.account.AccountStore]).
 *
 * Schemas are exported to `app/schemas/`; a version bump needs a Migration, never a
 * destructive fallback, because this is the user's library.
 */
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

        /** Tests swap in an in-memory database. */
        internal fun replaceForTest(db: VyoraDatabase?) {
            synchronized(this) { instance = db }
        }
    }
}
