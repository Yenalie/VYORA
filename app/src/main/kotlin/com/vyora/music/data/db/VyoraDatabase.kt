package com.vyora.music.data.db

import android.content.Context
import androidx.room.AutoMigration
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
        PlayEventEntity::class,
        LyricsCacheEntity::class,
        PendingScrobbleEntity::class,
    ],
    version = VyoraDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class VyoraDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun queue(): QueueDao
    abstract fun sync(): SyncDao
    abstract fun stats(): StatsDao
    abstract fun cache(): CacheDao

    companion object {
        const val NAME = "vyora.db"

        /**
         * Bump on every schema change. Additive changes get an [AutoMigration] above; anything Room can't
         * infer (renames, splits, data rewrites) goes in [Migrations.ALL]. The exported JSON in
         * `app/schemas/` must be committed, and [com.vyora.music.MigrationTest] must cover the new step.
         */
        const val VERSION = 3

        @Volatile
        private var instance: VyoraDatabase? = null

        fun get(context: Context): VyoraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, VyoraDatabase::class.java, NAME)
                    .addMigrations(*Migrations.ALL)
                    .build()
                    .also { instance = it }
            }

        internal fun replaceForTest(db: VyoraDatabase?) {
            synchronized(this) { instance = db }
        }
    }
}
