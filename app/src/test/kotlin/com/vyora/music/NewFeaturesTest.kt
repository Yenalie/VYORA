package com.vyora.music

import android.media.AudioDeviceInfo
import com.vyora.music.data.backup.Backup
import com.vyora.music.data.db.PlayEventEntity
import com.vyora.music.data.importer.SpotifyImport
import com.vyora.music.data.local.LosslessOutput
import com.vyora.music.data.lyrics.LyricLine
import com.vyora.music.data.lyrics.LyricWord
import com.vyora.music.data.lyrics.Lyrics
import com.vyora.music.data.lyrics.LyricsJson
import com.vyora.music.data.lyrics.LyricsRepository
import com.vyora.music.data.lyrics.PortatoLyrics
import com.vyora.music.data.lyrics.Ttml
import com.vyora.music.data.scrobble.Scrobbler
import com.vyora.music.data.server.MusicServer
import com.vyora.music.data.settings.AppSettings
import com.vyora.music.data.settings.AudioQuality
import com.vyora.music.data.settings.effectiveQuality
import com.vyora.music.data.youtube.SimpleItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {

    @Test fun ttmlTimes() {
        assertEquals(12_345L, Ttml.time("12.345"))
        assertEquals(12_300L, Ttml.time("12.3s"))
        assertEquals(62_345L, Ttml.time("1:02.345"))
        assertEquals(3_723_456L, Ttml.time("1:02:03.456"))
        assertNull(Ttml.time("abc"))
    }

    @Test fun ttmlWordSyncedLinesSkipBackgroundVocals() {
        val ttml = """<tt><body><div>
            <p begin="1.0" end="3.0"><span begin="1.0" end="1.5">Hello</span> <span begin="1.5" end="2.0">wor</span><span begin="2.0" end="2.5">ld</span><span ttm:role="x-bg"><span begin="2.5" end="3.0">(ooh)</span></span></p>
            <p begin="0:04.000" end="0:05.000">Plain &amp; simple</p>
        </div></body></tt>"""
        val lines = Ttml.parse(ttml)
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals("Hello world", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals("Hello", lines[0].words[0].text)
        assertEquals("world", lines[0].words[1].text)
        assertEquals(2, lines[0].words[1].syllables.size)
        assertEquals("Plain & simple", lines[1].text)
        assertFalse(lines[1].hasWords)
    }

    @Test fun portatoQrcMergesSyllablesIntoTimedWords() {
        val response = """{"data":{"lyrics":"[1000,2000](1000,300)Hel(1300,400)lo (1700,500)world "}}"""
        val line = PortatoLyrics.parse(response).single()
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words.size)
        assertEquals("Hello", line.words[0].text)
        assertEquals(2, line.words[0].syllables.size)
        assertEquals(1000L, line.words[0].startMs)
    }

    @Test fun enhancedLrcWords() {
        val words = LyricsRepository.enhancedWords("<00:12.34>Hati <00:12.80>hati <00:13.50>di jalan")
        assertEquals(3, words.size)
        assertEquals(12_340L, words[0].startMs)
        assertEquals(12_800L, words[0].endMs)
        assertEquals("di jalan", words[2].text)
        assertTrue(LyricsRepository.enhancedWords("plain line").isEmpty())
        val lines = LyricsRepository.parseLrc("[00:12.34]<00:12.34>Hati <00:12.80>hati")
        assertEquals("Hati hati", lines.single().text)
    }

    @Test fun lyricsCacheRoundTrip() {
        val l = Lyrics(listOf(LyricLine(1000, "a b", 2000, listOf(LyricWord(1000, 1500, "a "), LyricWord(1500, 2000, "b")))), "a b", "BetterLyrics")
        assertEquals(l, LyricsJson.decode(LyricsJson.encode(l)))
    }

    @Test fun lastFmSignatureSortsAndSkipsFormat() {
        // md5("api_keyxmethodauth.getMobileSessionpasswordpusernameusecret")
        val sig = Scrobbler.signature(
            mapOf("username" to "u", "password" to "p", "method" to "auth.getMobileSession", "api_key" to "x", "format" to "json"), "secret",
        )
        val expected = java.security.MessageDigest.getInstance("MD5")
            .digest("api_keyxmethodauth.getMobileSessionpasswordpusernameusecret".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, sig)
    }

    @Test fun subsonicTokenAndUrl() {
        // Example from the Subsonic API docs: password "sesame", salt "c19b2d" → 26719a1196d2a940705a59634eb18eab
        assertEquals("26719a1196d2a940705a59634eb18eab", MusicServer.token("sesame", "c19b2d"))
        assertEquals("https://music.example.com/", MusicServer.normalize("music.example.com/")?.toString())
        assertEquals("http://192.168.1.2:4533/", MusicServer.normalize(" http://192.168.1.2:4533 ")?.toString())
        assertNull(MusicServer.normalize(""))
    }

    @Test fun serverIdsAreNotYouTube() {
        assertTrue(SimpleItem.isYouTubeId("dQw4w9WgXcQ"))
        assertFalse(SimpleItem.isYouTubeId("srv:123"))
        assertFalse(SimpleItem.isYouTubeId("srvalbum:9"))
        assertFalse(SimpleItem.isYouTubeId(SimpleItem.LOCAL_PREFIX + "5"))
    }

    @Test fun spotifyLinks() {
        assertEquals(SpotifyImport.Source("playlist", "37i9dQZF1DXcBWIGoYBM5M"), SpotifyImport.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals(SpotifyImport.Source("album", "4aawyAB9vmqN3uQ7FjRGTy"), SpotifyImport.parse("https://open.spotify.com/intl-id/album/4aawyAB9vmqN3uQ7FjRGTy"))
        assertEquals("playlist", SpotifyImport.parse("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")?.kind)
        assertNull(SpotifyImport.parse("https://youtube.com/watch?v=x"))
    }

    @Test fun spotifyEmbedParse() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Galau","trackList":[{"title":"Sial","subtitle":"Mahalini","duration":240000},{"title":"","subtitle":"x"}]}}}}}}</script></html>"""
        val l = SpotifyImport.parseEmbed(html)!!
        assertEquals("Galau", l.name)
        assertEquals(listOf(SpotifyImport.Track("Sial", "Mahalini", 240000)), l.tracks)
        assertNull(SpotifyImport.parseEmbed("<html></html>"))
    }

    @Test fun spotifyMatchPrefersSameSong() {
        val t = SpotifyImport.Track("Sial", "Mahalini", 240_000)
        val right = SimpleItem("a", "Sial", "Mahalini", null, "", duration = 241)
        val cover = SimpleItem("b", "Sial (Cover)", "Someone", null, "", duration = 300)
        assertTrue(SpotifyImport.score(t, right) > SpotifyImport.score(t, cover))
    }

    @Test fun backupRoundTrip() {
        val song = SimpleItem("abcdefghijk", "Sial", "Mahalini", null, "u", duration = 240)
        val c = Backup.Contents(
            settings = mapOf("theme" to "DARK", "crossfade" to true, "speed" to 1.25f, "n" to 3, "big" to 5_000_000_000L),
            liked = listOf(song), saved = emptyList(),
            playlists = listOf("Galau" to listOf(song)),
            events = listOf(PlayEventEntity(itemId = song.id, title = "Sial", artist = "Mahalini", thumbnail = null, playedAt = 10, listenedMs = 30_000)),
        )
        val back = Backup.decode(Backup.encode(c))
        assertEquals(c.settings, back.settings)
        assertEquals(c.liked.map { it.id }, back.liked.map { it.id })
        assertEquals("Galau", back.playlists.single().first)
        assertEquals(30_000L, back.events.single().listenedMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun backupRejectsOtherFiles() { Backup.decode("""{"app":"Other"}""") }

    @Test fun mobileQualityOnlyWhenMetered() {
        val s = AppSettings(audioQuality = AudioQuality.HIGH, mobileQuality = AudioQuality.LOW, separateMobileQuality = true)
        assertEquals(AudioQuality.HIGH, s.effectiveQuality(metered = false))
        assertEquals(AudioQuality.LOW, s.effectiveQuality(metered = true))
        assertEquals(AudioQuality.HIGH, s.copy(separateMobileQuality = false).effectiveQuality(metered = true))
    }

    @Test fun losslessOutputDecision() {
        assertTrue(LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_USB_HEADSET), null).capable)
        assertEquals(LosslessOutput.Kind.WIRED, LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), null).via)
        assertFalse(LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), null).capable)
        assertTrue(LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), null).bluetoothCodecUnknown)
        assertTrue(LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), "LDAC").capable)
        assertFalse(LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), "AAC").capable)
        assertTrue(LosslessOutput.isLosslessBluetoothCodec("Samsung Seamless", 96_000))
        assertFalse(LosslessOutput.isLosslessBluetoothCodec("SSC", 48_000))
    }

    @Test fun indonesianFoldersMatch() {
        val a = File("src/main/res/values-in/strings.xml").readText()
        val b = File("src/main/res/values-id/strings.xml").readText()
        assertEquals(a, b)
    }

    @Test fun licenseIsBundled() {
        assertTrue(File("src/main/assets/licenses/GPL-3.0.txt").readText().contains("GNU GENERAL PUBLIC LICENSE"))
        assertNotNull(File("src/main/assets/licenses/NOTICE.txt").takeIf { it.isFile })
    }
}
