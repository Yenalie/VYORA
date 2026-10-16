package com.vyora.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val url: String,
    val duration: Long,
    val type: String,
    val count: Long,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    @ColumnInfo(name = "is_video") val isVideo: Boolean,
) {
    fun toItem() = SimpleItem(
        id = id, title = title, artist = artist, thumbnail = thumbnail, url = url,
        duration = duration, type = type, count = count, artistId = artistId, isVideo = isVideo,
    )

    companion object {
        fun of(item: SimpleItem) = ItemEntity(
            id = item.id, title = item.title, artist = item.artist, thumbnail = item.thumbnail,
            url = item.url.ifBlank { SimpleItem.watchUrl(item.id) }, duration = item.duration,
            type = item.type.ifBlank { ItemType.SONG }, count = item.count, artistId = item.artistId,
            isVideo = item.isVideo,
        )
    }
}

@Entity(
    tableName = "liked",
    foreignKeys = [ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("liked_at")],
)
data class LikedEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "liked_at") val likedAt: Long,
    val synced: Boolean = false,
)

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("played_at")],
)
data class HistoryEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
)

@Entity(
    tableName = "saved_collections",
    foreignKeys = [ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("saved_at")],
)
data class SavedEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    val synced: Boolean = false,
)

@Entity(tableName = "playlists", indices = [Index(value = ["remote_id"], unique = true)])
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlist_id", "item_id"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("item_id"), Index(value = ["playlist_id", "position"])],
)
data class PlaylistSongEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val position: Int,
    @ColumnInfo(name = "set_video_id") val setVideoId: String? = null,
)

@Entity(tableName = "search_history", indices = [Index("searched_at")])
data class SearchEntity(
    @PrimaryKey val key: String,
    val query: String,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
)

@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey val position: Int,
    @ColumnInfo(name = "item_id") val itemId: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val url: String,
    val duration: Long,
    val type: String,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    @ColumnInfo(name = "is_video") val isVideo: Boolean,
    val autoplay: Boolean,
) {
    fun toItem() = SimpleItem(
        id = itemId, title = title, artist = artist, thumbnail = thumbnail, url = url,
        duration = duration, type = type, artistId = artistId, isVideo = isVideo,
    )

    companion object {
        fun of(position: Int, item: SimpleItem, autoplay: Boolean) = QueueEntity(
            position = position, itemId = item.id, title = item.title, artist = item.artist,
            thumbnail = item.thumbnail, url = item.url.ifBlank { SimpleItem.watchUrl(item.id) },
            duration = item.duration, type = item.type, artistId = item.artistId,
            isVideo = item.isVideo, autoplay = autoplay,
        )
    }
}

@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "current_index") val index: Int,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
)

@Entity(tableName = "sync_actions", indices = [Index("target")])
data class SyncActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val target: String,
    val extra: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)

object SyncKind {
    const val LIKE = "like"
    const val UNLIKE = "unlike"
    const val SAVE = "save"
    const val UNSAVE = "unsave"
    const val PLAYLIST_CREATE = "pl_create"
    const val PLAYLIST_RENAME = "pl_rename"
    const val PLAYLIST_DELETE = "pl_delete"
    const val PLAYLIST_ADD = "pl_add"
    const val PLAYLIST_REMOVE = "pl_remove"
}

data class PlaylistSongRow(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "set_video_id") val setVideoId: String?,
    @androidx.room.Embedded val item: ItemEntity,
)

/**
 * One listen that counted (see [com.vyora.music.playback.service.ListeningTracker]). A snapshot of the
 * song's title/artist/artwork is kept so stats survive the song being removed from the library.
 * Added in schema v2.
 */
@Entity(tableName = "play_events", indices = [Index("played_at"), Index("item_id")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
)

/** Lyrics already looked up, so they open instantly and survive restarts. `payload` is null for "none found". */
@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    val payload: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/** A listen waiting to be sent to Last.fm / ListenBrainz (kept while offline). */
@Entity(tableName = "pending_scrobbles")
data class PendingScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artist: String,
    val title: String,
    val album: String?,
    @ColumnInfo(name = "duration_sec") val durationSec: Long,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    /** Bitmask of [com.vyora.music.data.scrobble.Scrobbler] services still owed this listen. */
    val targets: Int,
)
