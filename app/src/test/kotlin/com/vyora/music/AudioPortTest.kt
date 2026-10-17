package com.vyora.music

import com.grack.nanojson.JsonParser
import com.vyora.music.data.download.DownloadExport
import com.vyora.music.data.library.HomeFeed
import com.vyora.music.data.library.Recommend
import com.vyora.music.data.server.MusicServer
import com.vyora.music.data.settings.AudioQuality
import com.vyora.music.data.settings.DownloadQuality
import com.vyora.music.data.settings.OutputPcmMode
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.Loudness
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.local.LosslessOutput
import com.vyora.music.playback.audio.OutputPolicy
import com.vyora.music.ui.screens.SearchTab
import com.vyora.music.ui.screens.SearchViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.9.2: BitChord audio ports, recommendations and Top Results. */
class AudioPortTest {
    private fun song(id: String, title: String, artist: String, video: Boolean = false) =
        SimpleItem(id, title, artist, null, "", isVideo = video)

    @Test fun sameRecordingAcrossVideoAndCatalogueUploads() {
        val a = song("aaaaaaaaaa1", "Hati-Hati di Jalan", "Tulus")
        val b = song("bbbbbbbbbb2", "Hati-Hati di Jalan (Official Music Video)", "Tulus - Topic")
        val c = song("ccccccccc3", "Hati-Hati di Jalan", "Someone Else")
        assertTrue(Recommend.isSameRecording(a, b))
        assertFalse(Recommend.isSameRecording(a, c))
    }

    @Test fun extendCapsArtistsSkipsDuplicatesAndPrefersSongs() {
        val seed = song("seed0000001", "Seed", "X")
        val pool = listOf(
            song("v1", "One (Official Video)", "A", video = true),
            song("s1", "One", "A"),
            song("s2", "Two", "A"),
            song("s3", "Three", "A"),
            song("s4", "Four", "B"),
            song("seed0000001", "Seed", "X"),
        )
        val out = Recommend.extend(listOf(seed), pool, limit = 10)
        assertEquals(listOf("s1", "s2", "s4"), out.map { it.id })
        val excluded = Recommend.extend(listOf(seed), pool, limit = 10, exclude = setOf("s1"))
        assertFalse(excluded.any { it.id == "s1" })
    }

    @Test fun topResultsPutExactArtistFirstAndMixKinds() {
        fun of(prefix: String, n: Int, type: String = ItemType.SONG) =
            (1..n).map { SimpleItem("$prefix$it", "$prefix title $it", "art", null, "", type = type) }
        val tabs = mapOf(
            SearchTab.SONGS to of("s", 8),
            SearchTab.ARTISTS to listOf(SimpleItem("ar0", "Tulus", "", null, "", type = ItemType.ARTIST)) + of("ar", 3, ItemType.ARTIST),
            SearchTab.ALBUMS to of("al", 5, ItemType.ALBUM),
            SearchTab.PLAYLISTS to of("pl", 5, ItemType.PLAYLIST),
            SearchTab.VIDEOS to of("v", 5),
        )
        val out = SearchViewModel.topResults(tabs, SearchViewModel.normalize("Tulus"))
        assertEquals("ar0", out.first().id)
        assertEquals(listOf("s1", "s2", "al1"), out.drop(1).take(3).map { it.id })
        assertEquals(6, out.count { it.id.startsWith("s") })
        assertEquals(out.size, out.map { it.id }.toSet().size)
    }

    @Test fun loudnessFromPlayerResponseAndGainBounds() {
        val player = JsonParser.`object`().from("""{"playerConfig":{"audioConfig":{"loudnessDb":4.5}}}""")
        assertEquals(4.5, Loudness.fromResponses(player, emptyList())!!, 1e-9)
        assertNull(Loudness.fromResponses(null, emptyList()))
        assertEquals(Math.pow(10.0, -4.5 / 20).toFloat(), Loudness.gainFor(4.5), 1e-5f)
        assertEquals(Math.pow(10.0, -15.0 / 20).toFloat(), Loudness.gainFor(40.0), 1e-5f)
        assertEquals(Math.pow(10.0, 3.0 / 20).toFloat(), Loudness.gainFor(-20.0), 1e-5f)
    }

    @Test fun exportSniffsTheRealContainer() {
        fun b(vararg v: Int) = ByteArray(12).also { v.forEachIndexed { i, x -> it[i] = x.toByte() } }
        assertEquals("flac", DownloadExport.sniff(b(0x66, 0x4C, 0x61, 0x43))!!.extension)
        assertEquals("webm", DownloadExport.sniff(b(0x1A, 0x45, 0xDF, 0xA3))!!.extension)
        assertEquals("m4a", DownloadExport.sniff(b(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70))!!.extension)
        assertEquals("mp3", DownloadExport.sniff(b(0x49, 0x44, 0x33))!!.extension)
        assertNull(DownloadExport.sniff(b(1, 2, 3, 4)))
        val name = DownloadExport.fileName(song("x", "A/B: C?", "Tulus"), DownloadExport.Container("flac", "audio/flac"))
        assertEquals("Tulus - A_B_ C_.flac", name)
    }

    @Test fun serverQualityRewritesTheStreamUrl() {
        val url = "https://music.example/rest/stream?id=42&format=raw&u=me"
        assertEquals("42", MusicServer.streamSongIdOf(url))
        val mp3 = MusicServer.withQuality(url, 128)
        assertTrue(mp3.contains("format=mp3") && mp3.contains("maxBitRate=128") && !mp3.contains("format=raw"))
        assertTrue(MusicServer.withQuality(mp3, null).contains("format=raw"))
        assertNull(MusicServer.streamSongIdOf("https://music.example/rest/getCoverArt?id=1"))
    }

    @Test fun ladderAndPolicy() {
        assertTrue(AudioQuality.LOSSLESS.keepsLossless)
        assertNull(AudioQuality.LOSSLESS.serverKbps)
        assertEquals(AudioQuality.LOSSLESS, DownloadQuality.LOSSLESS.stream)
        assertFalse(OutputPolicy.shouldUseFloat(OutputPcmMode.PCM_16, LosslessOutput.Kind.USB, true))
        assertFalse(OutputPolicy.shouldUseFloat(OutputPcmMode.FLOAT_32, LosslessOutput.Kind.SPEAKER, true))
        assertTrue(OutputPolicy.shouldUseFloat(OutputPcmMode.FLOAT_32, LosslessOutput.Kind.USB, true))
    }

    @Test fun rediscoverSkipsWhatWasHeardRecently() {
        val recent = listOf(song("1", "a", "A"))
        val liked = listOf(song("1", "a", "A"), song("2", "b", "B"))
        assertEquals(listOf("2"), HomeFeed.rediscover(recent, liked).map { it.id })
    }
}
