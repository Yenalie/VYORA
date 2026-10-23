package com.vyora.music

import com.vyora.music.data.motion.MotionArtwork
import com.vyora.music.data.motion.MotionMatch
import com.vyora.music.data.motion.MotionSource
import com.vyora.music.data.spotify.SpotifyAccount
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotionArtworkTest {
    private fun res(path: String) = javaClass.classLoader!!.getResource(path)!!.readText()

    @Test fun titlesMatchWithoutVideoNoise() {
        assertEquals("anti hero", MotionMatch.normalize("Anti-Hero (Official Music Video)"))
        assertTrue(MotionMatch.score("Anti-Hero (Official Video)", "Taylor Swift", "Anti-Hero", "Taylor Swift", 200_000, 200_690) >= MotionMatch.ACCEPT)
        assertTrue(MotionMatch.score("Anti-Hero", "Someone Else", "Anti-Hero", "Taylor Swift", 0, 0) < MotionMatch.ACCEPT)
        assertTrue(MotionMatch.score("Hati-Hati di Jalan", "Tulus", "Interaksi", "Tulus", 0, 0) == 0)
    }

    @Test fun itunesSearchPicksTheAlbum() {
        val id = MotionArtwork.bestITunesAlbum(JSONObject(res("motion/itunes_search.json")), "Anti-Hero", "Taylor Swift", 200_690)
        assertNotNull(id)
    }

    @Test fun appleAlbumPrefersTheTallLoop() {
        val art = MotionArtwork.parseAppleAlbum(JSONObject(res("motion/apple_album.json")))!!
        assertEquals(MotionSource.APPLE_TALL, art.source)
        assertEquals(0.75f, art.aspect, 0.001f)
        assertTrue(art.url.endsWith(".m3u8"))
        assertNotNull(art.background)
        assertNull(MotionArtwork.parseAppleAlbum(JSONObject("""{"data":[{"attributes":{"name":"x"}}]}""")))
    }

    @Test fun renditionIsTheLargestH264UnderTheLimit() {
        val master = res("motion/master.m3u8")
        val wifi = MotionArtwork.pickRendition(master, 700)!!
        assertTrue(wifi, wifi.endsWith("_664x886_-.mp4"))
        val cell = MotionArtwork.pickRendition(master, 500)!!
        assertTrue(cell, cell.contains("_486x648_-.mp4"))
        assertNull(MotionArtwork.pickRendition("#EXTM3U\n", 700))
    }

    @Test fun appleTokenExpiryIsRead() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1798583676}""".toByteArray())
        assertEquals(1798583676000L, MotionArtwork.tokenExpiry("eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.$payload.sig"))
    }

    @Test fun spotifySearchAndCanvasParse() {
        val uri = SpotifyAccount.bestTrackUri(JSONObject(res("spotify/search_tracks.json")), "Blinding Lights", "The Weeknd", 200_040)
        assertEquals("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", uri)
        val none = JSONObject("""{"data":{"trackUnion":{"__typename":"Track","canvas":null}}}""")
        assertNull(SpotifyAccount.parseCanvas(none))
        val some = JSONObject("""{"data":{"trackUnion":{"canvas":{"type":"VIDEO_LOOPING_RANDOM","url":"https://canvaz.scdn.co/upload/artist/x/video/abc.cnvs.mp4"}}}}""")
        assertEquals("https://canvaz.scdn.co/upload/artist/x/video/abc.cnvs.mp4", SpotifyAccount.parseCanvas(some))
    }
}
