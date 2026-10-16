package com.vyora.music

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vyora.music.data.db.DayRow
import com.vyora.music.data.db.PlayEventEntity
import com.vyora.music.data.db.VyoraDatabase
import com.vyora.music.playback.service.ListeningTracker
import com.vyora.music.ui.screens.RecapPeriod
import com.vyora.music.ui.screens.dailySeries
import com.vyora.music.ui.screens.recapSince
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecapTest {
    private lateinit var db: VyoraDatabase
    private val day = 86_400_000L

    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, VyoraDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    private fun event(id: String, artist: String, at: Long, ms: Long = 180_000L, title: String = "T$id") =
        PlayEventEntity(itemId = id, title = title, artist = artist, thumbnail = "art_$id", playedAt = at, listenedMs = ms)

    @Test fun listensCountLikeScrobbles() {
        assertTrue(ListeningTracker.counts(30_000, 240_000))
        assertFalse(ListeningTracker.counts(29_999, 240_000))
        // Short songs: half of the song, but never less than 10 s.
        assertTrue(ListeningTracker.counts(21_000, 40_000))
        assertFalse(ListeningTracker.counts(15_000, 40_000))
        assertFalse(ListeningTracker.counts(9_000, 12_000))
        assertTrue(ListeningTracker.counts(10_000, 12_000))
        assertFalse(ListeningTracker.counts(0, 0))
    }

    @Test fun statsAggregateTopSongsArtistsAndTotals() = runBlocking {
        val stats = db.stats()
        val now = 100 * day
        stats.insert(event("a", "Tulus", now - 1000))
        stats.insert(event("a", "Tulus", now - 2000, title = "Old title"))
        stats.insert(event("a", "Tulus", now - 500, title = "New title"))
        stats.insert(event("b", "Tulus", now - 3000))
        stats.insert(event("c", "Hindia", now - 4000, ms = 60_000))
        stats.insert(event("d", "Nadin", now - 40 * day)) // outside 30 days

        val since = now - 30 * day
        val totals = stats.totals(since).first()
        assertEquals(5, totals.plays)
        assertEquals(4 * 180_000L + 60_000L, totals.listenedMs)
        assertEquals(3, totals.songs)
        assertEquals(2, totals.artists)

        val songs = stats.topSongs(since, 10).first()
        assertEquals(listOf("a", "b", "c"), songs.map { it.itemId })
        assertEquals(3, songs.first().plays)
        assertEquals("New title", songs.first().title) // newest snapshot wins

        val artists = stats.topArtists(since, 10).first()
        assertEquals(listOf("Tulus", "Hindia"), artists.map { it.artist })
        assertEquals(4, artists.first().plays)

        assertEquals(6, stats.totals(0).first().plays)
        stats.clear()
        assertEquals(0, stats.totals(0).first().plays)
    }

    @Test fun dailyBucketsSplitAtLocalMidnight() = runBlocking {
        val zone = TimeZone.getTimeZone("Asia/Makassar") // UTC+8
        val offset = zone.getOffset(0L).toLong()
        val stats = db.stats()
        // 23:30 and 00:30 local time on consecutive days.
        val midnight = 200 * day - offset
        stats.insert(event("x", "A", midnight - 30 * 60_000, ms = 1_000))
        stats.insert(event("y", "A", midnight + 30 * 60_000, ms = 2_000))
        val rows = stats.daily(0, offset).first()
        assertEquals(listOf(199L, 200L), rows.map { it.day })
        val series = dailySeries(rows, 3, now = midnight + 60 * 60_000, zone = zone)
        assertEquals(listOf(0L, 1_000L, 2_000L), series)
    }

    @Test fun periodsStartAtLocalMidnight() {
        val zone = TimeZone.getTimeZone("UTC")
        val now = 10 * day + 5 * 3_600_000L
        assertEquals(4 * day, recapSince(RecapPeriod.WEEK, now, zone))
        assertEquals(0L, recapSince(RecapPeriod.ALL, now, zone))
        assertEquals(listOf(0L, 0L, 5L), dailySeries(listOf(DayRow(10, 5)), 3, now, zone))
    }
}
