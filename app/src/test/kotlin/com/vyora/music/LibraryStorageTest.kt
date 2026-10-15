package com.vyora.music

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vyora.music.data.db.HistoryEntity
import com.vyora.music.data.db.ItemEntity
import com.vyora.music.data.db.LegacyFormat
import com.vyora.music.data.db.LegacyImport
import com.vyora.music.data.db.LikedEntity
import com.vyora.music.data.db.PlaylistEntity
import com.vyora.music.data.db.PlaylistSongEntity
import com.vyora.music.data.db.SavedEntity
import com.vyora.music.data.db.SearchEntity
import com.vyora.music.data.db.SyncActionEntity
import com.vyora.music.data.db.SyncKind
import com.vyora.music.data.db.VyoraDatabase
import com.vyora.music.data.library.UserPlaylist
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.SimpleItemJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Plain Application: VyoraApp.onCreate starts the real library (and its one-time legacy import
// in the background), which would race this test for the same SharedPreferences.
@Config(sdk = [35], application = android.app.Application::class)
class LibraryStorageTest {
    private val song = SimpleItem("abcdefghijk", "Hati-Hati di Jalan", "Tulus", "https://x/y.jpg", "https://www.youtube.com/watch?v=abcdefghijk", 242, "song")
    private val video = song.copy(id = "videoidxxxx", title = "Interaksi", isVideo = true)
    private val album = SimpleItem("OLAK5uy_x", "Manusia", "Tulus", null, "u", 0, ItemType.ALBUM, count = 10)

    private lateinit var db: VyoraDatabase

    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, VyoraDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    // ----------------------------------------------------------------------- formats

    @Test fun jsonRoundTripKeepsEveryField() {
        assertEquals(listOf(song, album, video), SimpleItemJson.decode(SimpleItemJson.encode(listOf(song, album, video))))
    }

    @Test fun entityRoundTripKeepsEveryField() {
        listOf(song, video, album.copy(artistId = "UC1")).forEach { assertEquals(it, ItemEntity.of(it).toItem()) }
    }

    @Test fun legacyPlaylistsAndSearchesAreRead() {
        val raw = """[{"id":"vy_1","name":"Perjalanan","created":123,"songs":[${SimpleItemJson.toJson(song)}]},{"name":"no id"}]"""
        val playlists = LegacyFormat.decodePlaylists(raw)
        assertEquals(1, playlists.size)
        assertEquals(LegacyFormat.Playlist("vy_1", "Perjalanan", listOf(song), 123L), playlists[0])
        assertEquals(listOf("Tulus", "Raisa"), LegacyFormat.decodeSearches("""["Tulus","tulus","Raisa",""]"""))
    }

    @Test fun legacyCorruptDataIsIgnored() {
        assertTrue(LegacyFormat.decodeItems("{not json").isEmpty())
        assertTrue(LegacyFormat.decodePlaylists("[oops").isEmpty())
        assertTrue(LegacyFormat.decodeSearches(null).isEmpty())
    }

    @Test fun legacyDelimitedFormat() {
        val items = LegacyFormat.decodeDelimited("abcdefghijk###Hati###Tulus###https://t|||bad|||x2###Monokrom###Tulus###")
        assertEquals(listOf("abcdefghijk", "x2"), items.map { it.id })
        assertNull(items[1].thumbnail)
    }

    @Test fun mosaicUsesDistinctCovers() {
        val songs = (1..6).map { song.copy(id = "id$it", thumbnail = "c${it % 3}") }
        assertEquals(listOf("c1", "c2", "c0"), UserPlaylist("p", "n", songs, 0).covers)
    }

    // ---------------------------------------------------------------------- database

    @Test fun likedNewestFirstAndOrphansCleaned() = runBlocking {
        val dao = db.library()
        dao.upsertItems(listOf(ItemEntity.of(song), ItemEntity.of(video)))
        dao.upsertLiked(LikedEntity(song.id, 1))
        dao.upsertLiked(LikedEntity(video.id, 2))
        assertEquals(listOf(video, song), dao.likedFlow().first().map { it.toItem() })

        dao.deleteLiked(video.id)
        assertEquals(1, dao.deleteOrphanItems())
        assertEquals(listOf(song), dao.likedFlow().first().map { it.toItem() })
    }

    @Test fun historyIsTrimmedToNewest() = runBlocking {
        val dao = db.library()
        val songs = (1..5).map { song.copy(id = "s$it") }
        dao.upsertItems(songs.map(ItemEntity::of))
        songs.forEachIndexed { i, s -> dao.upsertHistory(HistoryEntity(s.id, i.toLong())) }
        dao.trimHistory(3)
        assertEquals(listOf("s5", "s4", "s3"), dao.historyFlow().first().map { it.id })
    }

    @Test fun playlistKeepsOrderAndCascades() = runBlocking {
        val dao = db.library()
        val songs = (1..3).map { song.copy(id = "p$it") }
        dao.upsertItems(songs.map(ItemEntity::of))
        dao.insertPlaylist(PlaylistEntity("vy_1", "Jalan", 10))
        songs.reversed().forEach { dao.insertPlaylistSong(PlaylistSongEntity("vy_1", it.id, dao.lastPosition("vy_1") + 1)) }
        // Adding a song twice is ignored.
        assertEquals(-1L, dao.insertPlaylistSong(PlaylistSongEntity("vy_1", "p1", 9)))
        val rows = dao.playlistSongsFlow().first()
        assertEquals(listOf("p3", "p2", "p1"), rows.map { it.item.id })

        dao.replacePlaylistSongs("vy_1", songs.map(ItemEntity::of), listOf("h1", null, "h3"))
        assertEquals(listOf("h1", null, "h3"), dao.playlistSongs("vy_1").map { it.setVideoId })

        dao.deletePlaylist("vy_1")
        assertTrue(dao.playlistSongsFlow().first().isEmpty())
    }

    @Test fun savedTypesAndSyncFlags() = runBlocking {
        val dao = db.library()
        val playlist = album.copy(id = "PLx", type = ItemType.PLAYLIST)
        dao.upsertItems(listOf(ItemEntity.of(album), ItemEntity.of(playlist)))
        dao.upsertSaved(listOf(SavedEntity(album.id, 1, synced = true), SavedEntity(playlist.id, 2)))
        assertEquals(listOf(album.id), dao.syncedSavedIds(ItemType.ALBUM))
        assertEquals(listOf(playlist.id), dao.unsyncedSavedIds(ItemType.PLAYLIST))
        dao.deleteSyncedSaved()
        assertEquals(listOf(playlist.id), dao.savedRows().map { it.itemId })
    }

    @Test fun searchesDedupeCaseInsensitively() = runBlocking {
        val dao = db.library()
        dao.upsertSearch(SearchEntity("tulus", "tulus", 1))
        dao.upsertSearch(SearchEntity("raisa", "Raisa", 2))
        dao.upsertSearch(SearchEntity("tulus", "Tulus", 3))
        assertEquals(listOf("Tulus", "Raisa"), dao.searchesFlow().first())
        dao.trimSearches(1)
        assertEquals(listOf("Tulus"), dao.searchesFlow().first())
    }

    @Test fun pendingChangesCancelOut() = runBlocking {
        val sync = db.sync()
        sync.insert(SyncActionEntity(kind = SyncKind.LIKE, target = song.id))
        assertEquals(1, sync.deleteFor(song.id, listOf(SyncKind.LIKE, SyncKind.UNLIKE)))
        sync.insert(SyncActionEntity(kind = SyncKind.UNLIKE, target = song.id))
        sync.insert(SyncActionEntity(kind = SyncKind.PLAYLIST_ADD, target = "vy_1", extra = song.id))
        assertEquals(listOf(SyncKind.UNLIKE, SyncKind.PLAYLIST_ADD), sync.all().map { it.kind })
        assertEquals(setOf(song.id, "vy_1"), sync.pendingTargets().toSet())
    }

    @Test fun legacyLibraryIsImportedOnce() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("vyora_library", Context.MODE_PRIVATE).edit()
            .putString("liked_songs", "aaaaaaaaaaa###Satu###Tulus###|||bbbbbbbbbbb###Dua###Raisa###")
            .putString("recent_songs", "bbbbbbbbbbb###Dua###Raisa###").commit()
        ctx.getSharedPreferences("vyora_search", Context.MODE_PRIVATE).edit()
            .putString("recent_queries", """["Tulus","Hindia"]""").commit()

        LegacyImport.runOnce(ctx, db)
        val dao = db.library()
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), dao.likedFlow().first().map { it.id })
        assertEquals(listOf("bbbbbbbbbbb"), dao.historyFlow().first().map { it.id })
        assertEquals(listOf("Tulus", "Hindia"), dao.searchesFlow().first())
        assertTrue(ctx.getSharedPreferences("vyora_library", Context.MODE_PRIVATE).all.isEmpty())

        // Second run is a no-op even if old data reappears.
        dao.clearHistory()
        ctx.getSharedPreferences("vyora_library", Context.MODE_PRIVATE).edit().putString("recent_songs", "ccccccccccc###Tiga###X###").commit()
        LegacyImport.runOnce(ctx, db)
        assertTrue(dao.historyFlow().first().isEmpty())
    }
}
