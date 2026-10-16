package com.vyora.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class TopSongRow(
    @ColumnInfo(name = "item_id") val itemId: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val plays: Int,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
)

data class TopArtistRow(
    val artist: String,
    val thumbnail: String?,
    val plays: Int,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
)

data class DayRow(
    val day: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
)

data class TotalsRow(
    val plays: Int,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    val songs: Int,
    val artists: Int,
)

@Dao
interface StatsDao {
    @Insert
    suspend fun insert(event: PlayEventEntity)

    @Insert
    suspend fun insertAll(events: List<PlayEventEntity>)

    @Query("SELECT * FROM play_events ORDER BY played_at")
    suspend fun allEvents(): List<PlayEventEntity>

    @Query(
        "SELECT COUNT(*) AS plays, COALESCE(SUM(listened_ms), 0) AS listened_ms, " +
            "COUNT(DISTINCT item_id) AS songs, COUNT(DISTINCT artist) AS artists " +
            "FROM play_events WHERE played_at >= :since"
    )
    fun totals(since: Long): Flow<TotalsRow>

    // MAX(played_at) picks the newest snapshot of title/artwork for a song.
    @Query(
        "SELECT item_id, title, artist, thumbnail, plays, listened_ms FROM (" +
            "SELECT item_id, title, artist, thumbnail, MAX(played_at) AS last_at, COUNT(*) AS plays, SUM(listened_ms) AS listened_ms " +
            "FROM play_events WHERE played_at >= :since GROUP BY item_id) " +
            "ORDER BY plays DESC, listened_ms DESC, last_at DESC LIMIT :limit"
    )
    fun topSongs(since: Long, limit: Int): Flow<List<TopSongRow>>

    @Query(
        "SELECT artist, thumbnail, plays, listened_ms FROM (" +
            "SELECT artist, thumbnail, MAX(played_at) AS last_at, COUNT(*) AS plays, SUM(listened_ms) AS listened_ms " +
            "FROM play_events WHERE played_at >= :since AND artist != '' GROUP BY artist) " +
            "ORDER BY plays DESC, listened_ms DESC, last_at DESC LIMIT :limit"
    )
    fun topArtists(since: Long, limit: Int): Flow<List<TopArtistRow>>

    /** Listening per local day; [offsetMs] is the device's UTC offset so days split at local midnight. */
    @Query(
        "SELECT ((played_at + :offsetMs) / 86400000) AS day, SUM(listened_ms) AS listened_ms " +
            "FROM play_events WHERE played_at >= :since GROUP BY day ORDER BY day"
    )
    fun daily(since: Long, offsetMs: Long): Flow<List<DayRow>>

    @Query("DELETE FROM play_events")
    suspend fun clear()
}
