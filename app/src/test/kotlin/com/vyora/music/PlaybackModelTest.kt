package com.vyora.music

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vyora.music.data.db.VyoraDatabase
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.playback.MediaItems
import com.vyora.music.playback.SleepTimer
import com.vyora.music.playback.service.QueueStore
import com.vyora.music.ui.screens.libraryArtists
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackModelTest {
    private val song = SimpleItem("E7kHvjvU6JY", "Hati-Hati di Jalan", "Tulus", "https://x/y.jpg",
        "https://www.youtube.com/watch?v=E7kHvjvU6JY", 242, "song", artistId = "UC_DHlXllTSMB8pTC38_leFg")

    @After fun reset() {
        SleepTimer.cancel()
        VyoraDatabase.replaceForTest(null)
    }

    @Test fun mediaItemRoundTripKeepsArtistAndAutoplayFlag() {
        val media = MediaItems.from(song, autoplay = true)
        assertTrue(MediaItems.isAutoplay(media))
        assertEquals(song, MediaItems.toSimpleItem(media))
        assertFalse(MediaItems.isAutoplay(MediaItems.from(song)))
    }

    @Test fun savedQueueKeepsAutoplayRows() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        VyoraDatabase.replaceForTest(Room.inMemoryDatabaseBuilder(ctx, VyoraDatabase::class.java).build())
        val other = song.copy(id = "nC4Fd1nhl50", title = "1000 Tahun Lamanya")
        QueueStore.save(ctx, listOf(MediaItems.from(song), MediaItems.from(other, autoplay = true)), 1, 30_000L)
        val saved = QueueStore.load(ctx)!!
        assertEquals(1, saved.index)
        assertEquals(30_000L, saved.positionMs)
        assertFalse(MediaItems.isAutoplay(saved.items[0]))
        assertTrue(MediaItems.isAutoplay(saved.items[1]))
        assertEquals("UC_DHlXllTSMB8pTC38_leFg", MediaItems.toSimpleItem(saved.items[1])?.artistId)
    }

    @Test fun sleepTimerModes() {
        SleepTimer.start(30)
        val left = SleepTimer.remainingMs()!!
        assertTrue(left in 29 * 60_000L..30 * 60_000L)
        SleepTimer.endOfTrack()
        assertNull(SleepTimer.remainingMs())
        assertEquals(SleepTimer.Mode.EndOfTrack, SleepTimer.mode.value)
        SleepTimer.cancel()
        assertNull(SleepTimer.mode.value)
    }

    @Test fun channelIdParsing() {
        assertEquals("UC_DHlXllTSMB8pTC38_leFg", YouTubeClient.channelIdOf("https://www.youtube.com/channel/UC_DHlXllTSMB8pTC38_leFg"))
        assertEquals("UCabc", YouTubeClient.channelIdOf("https://music.youtube.com/channel/UCabc?si=1"))
        assertNull(YouTubeClient.channelIdOf("https://www.youtube.com/@tulus"))
        assertNull(YouTubeClient.channelIdOf(null))
    }

    @Test fun libraryArtistsPickUpChannelIds() {
        val noId = song.copy(id = "a", artistId = null)
        val withId = song.copy(id = "b")
        val artists = libraryArtists(listOf(noId, withId), emptyList(), emptyList())
        assertEquals(1, artists.size)
        assertEquals("UC_DHlXllTSMB8pTC38_leFg", artists.single().id)
        assertEquals(noId.thumbnail, artists.single().art)
    }
}
