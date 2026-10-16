package com.vyora.music

import android.net.Uri
import com.vyora.music.data.discord.DiscordAccount
import com.vyora.music.data.discord.DiscordPresence
import com.vyora.music.data.discord.PresenceTrack
import com.vyora.music.data.settings.AppSettings
import com.vyora.music.data.settings.DiscordActivityKind
import com.vyora.music.data.spotify.SpotifyAccount
import com.vyora.music.ui.player.speedLabel
import com.vyora.music.ui.screens.settings.cleanUserAgent
import com.vyora.music.ui.screens.settings.isWebViewLoadableUrl
import com.vyora.music.ui.screens.settings.spotifyCookieOrigin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Discord OAuth / Rich Presence and Spotify web-player login, both ported from YumaPlayer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IntegrationsTest {

    @Test fun pkceChallengeIsUnpaddedBase64UrlSha256() {
        assertEquals(
            "DpW_nzyKLmXOCjIx2Ik4CEMHAv6AF_zNMXGb5Z9P0oI",
            DiscordAccount.challenge("dBjftJeZ4CVP-mB92K1uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun authorizeUrlCarriesPkceAndRedirect() {
        val s = DiscordAccount.createSession("1165706613961789445", "discord-1165706613961789445:/authorize/callback")
        val u = s.uri
        assertEquals("discord.com", u.host)
        assertEquals("/oauth2/authorize", u.path)
        assertEquals("1165706613961789445", u.getQueryParameter("client_id"))
        assertEquals("code", u.getQueryParameter("response_type"))
        assertEquals("S256", u.getQueryParameter("code_challenge_method"))
        assertEquals(DiscordAccount.challenge(s.verifier), u.getQueryParameter("code_challenge"))
        assertEquals("openid identify sdk.social_layer_presence", u.getQueryParameter("scope"))
        assertEquals(s.state, u.getQueryParameter("state"))
        assertTrue(s.verifier.length >= 43)
        assertFalse(s.verifier.contains('=') || s.verifier.contains('+') || s.verifier.contains('/'))
    }

    @Test fun redirectMustMatchSchemePathAndState() {
        val s = DiscordAccount.createSession("1", "discord-1:/authorize/callback")
        val scheme = "discord-1"
        fun check(uri: String) = DiscordAccount.validateRedirect(Uri.parse(uri), s, scheme)
        assertNull(check("discord-1:/authorize/callback?code=abc&state=${s.state}"))
        assertNotNull(check("discord-1:/authorize/callback?code=abc&state=wrong"))
        assertNotNull(check("discord-2:/authorize/callback?code=abc&state=${s.state}"))
        assertNotNull(check("discord-1:/elsewhere?code=abc&state=${s.state}"))
        assertNotNull(check("discord-1:/authorize/callback?state=${s.state}"))
        assertEquals("access_denied", check("discord-1:/authorize/callback?error=access_denied&state=${s.state}"))
        assertNotNull(DiscordAccount.validateRedirect(Uri.parse("discord-1:/authorize/callback?code=a&state=x"), null, scheme))
    }

    @Test fun discordUserAndAvatar() {
        val u = DiscordAccount.parseUser(JSONObject("""{"id":"80351110224678912","username":"nelly","global_name":"Nelly","avatar":"a_8342729096ea3675442027381ff50dfe"}"""))
        assertEquals("Nelly", u.name)
        assertEquals("nelly", u.username)
        assertEquals("https://cdn.discordapp.com/avatars/80351110224678912/a_8342729096ea3675442027381ff50dfe.gif?size=256", u.avatarUrl)
        val plain = DiscordAccount.parseUser(JSONObject("""{"id":"80351110224678912","username":"nelly","global_name":null,"avatar":null}"""))
        assertEquals("nelly", plain.name)
        assertTrue(plain.avatarUrl!!.startsWith("https://cdn.discordapp.com/embed/avatars/"))
    }

    @Test fun presenceActivityLooksLikeYumas() {
        val track = PresenceTrack(
            title = "Hati-Hati di Jalan", artist = "Tulus", album = "Manusia",
            startedAt = 1_000L, endsAt = 240_000L, youtubeId = "dQw4w9WgXcQ",
        )
        val a = DiscordPresence.activity(track, AppSettings(), "123456789012345678", "mp:external/cover", null)
        assertEquals(2, a.getInt("type"))
        assertEquals("123456789012345678", a.getString("application_id"))
        assertEquals("Hati-Hati di Jalan", a.getString("details"))
        assertEquals("Tulus", a.getString("state"))
        assertEquals(1_000L, a.getJSONObject("timestamps").getLong("start"))
        assertEquals(240_000L, a.getJSONObject("timestamps").getLong("end"))
        assertEquals("mp:external/cover", a.getJSONObject("assets").getString("large_image"))
        assertEquals("Manusia", a.getJSONObject("assets").getString("large_text"))
        val labels = a.getJSONArray("buttons")
        assertEquals("Listen on YouTube Music", labels.getString(0))
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", a.getJSONObject("metadata").getJSONArray("button_urls").getString(0))

        val paused = DiscordPresence.activity(track.copy(paused = true, youtubeId = null), AppSettings(discordButtons = false, discordActivity = DiscordActivityKind.PLAYING), "1", "mp:x", null)
        assertEquals(0, paused.getInt("type"))
        assertFalse(paused.has("timestamps"))
        assertEquals("Paused", paused.getJSONObject("assets").getString("small_text"))
        assertFalse(paused.has("buttons"))
    }

    @Test fun discordImagesThatNeedNoUpload() {
        assertEquals("mp:attachments/1/2/a.png", DiscordPresence.directImage("https://cdn.discordapp.com/attachments/1/2/a.png"))
        assertEquals("mp:external/abc", DiscordPresence.directImage("mp:external/abc"))
        assertEquals("123456789012345678", DiscordPresence.directImage("123456789012345678"))
        assertNull(DiscordPresence.directImage("https://lh3.googleusercontent.com/x"))
    }

    @Test fun identifyUsesBearerTokenAndSocialSdkFlags() {
        val d = DiscordPresence.identify("Bearer abc").getJSONObject("d")
        assertEquals("Bearer abc", d.getString("token"))
        assertEquals((1 shl 4) or (1 shl 5) or (1 shl 12) or (1 shl 16), d.getInt("capabilities"))
        assertEquals("VYORA", d.getJSONObject("properties").getString("browser"))
    }

    @Test fun spotifyTotpMatchesRfc6238() {
        // RFC 6238 SHA-1 vectors, secret "12345678901234567890", last six digits.
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("287082", SpotifyAccount.totp(secret, 59L))
        assertEquals("081804", SpotifyAccount.totp(secret, 1_111_111_109L))
        assertEquals("005924", SpotifyAccount.totp(secret, 1_234_567_890L))
    }

    @Test fun spotifyPicksNewestValidSecret() {
        val list = JSONArray("""[{"s":"GEZDGNBV","v":3},{"s":"not base32!","v":9},{"s":"MFRGG===","v":5},{"s":"MFRGG","v":0}]""")
        assertEquals("MFRGG===" to 5, SpotifyAccount.pickNuance(list))
        assertNull(SpotifyAccount.pickNuance(JSONArray("[]")))
    }

    @Test fun spotifyGraphqlBodyUsesPersistedQuery() {
        val body = SpotifyAccount.gqlBody("libraryV3", JSONObject().put("limit", 50))
        assertEquals("libraryV3", body.getString("operationName"))
        assertEquals(50, body.getJSONObject("variables").getInt("limit"))
        val pq = body.getJSONObject("extensions").getJSONObject("persistedQuery")
        assertEquals(1, pq.getInt("version"))
        assertEquals(SpotifyAccount.HASHES["libraryV3"], pq.getString("sha256Hash"))
    }

    @Test fun spotifyParsesProfilePlaylistsAndTracks() {
        val me = SpotifyAccount.parseProfile(JSONObject("""{"data":{"me":{"profile":{"uri":"spotify:user:abc","name":"Yena","avatar":{"sources":[{"url":"s","width":64},{"url":"l","width":300}]}}}}}"""))
        assertEquals("abc", me.id)
        assertEquals("Yena", me.name)
        assertEquals("l", me.avatarUrl)

        val lib = SpotifyAccount.parsePlaylists(JSONObject("""{"data":{"me":{"libraryV3":{"totalCount":2,"items":[
            {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:37i9","data":{"__typename":"Playlist","name":"Mix","images":{"items":[{"sources":[{"url":"img","width":640}]}]},"ownerV2":{"data":{"name":"Spotify"}}}}},
            {"item":{"__typename":"FolderResponseWrapper","_uri":"spotify:folder:1","data":{"name":"F"}}}
        ]}}}}"""))
        assertEquals(2, lib.total)
        assertEquals(1, lib.items.size)
        assertEquals("spotify:playlist:37i9", lib.items[0].uri)
        assertEquals("img", lib.items[0].imageUrl)
        assertEquals("Spotify", lib.items[0].owner)

        val track = """{"_uri":"spotify:track:1","data":{"__typename":"Track","name":"Teh Hijau","artists":{"items":[{"profile":{"name":"Tulus"}},{"profile":{"name":"X"}}]},"albumOfTrack":{"name":"Manusia","coverArt":{"sources":[{"url":"c","width":300}]}},"trackDuration":{"totalMilliseconds":200000}}}"""
        val liked = SpotifyAccount.parseLikedSongs(JSONObject("""{"data":{"me":{"library":{"tracks":{"totalCount":7,"items":[{"track":$track}]}}}}}"""))
        assertEquals(7, liked.total)
        val t = liked.items.single()
        assertEquals("Teh Hijau", t.title)
        assertEquals("Tulus, X", t.artist)
        assertEquals(200_000L, t.durationMs)
        assertEquals("c", t.imageUrl)

        val episode = """{"itemV2":{"_uri":"spotify:episode:9","data":{"__typename":"Episode","name":"Pod"}}}"""
        val pl = SpotifyAccount.parsePlaylistTracks(JSONObject("""{"data":{"playlistV2":{"name":"Mix","content":{"totalCount":2,"items":[{"itemV2":$track},$episode]}}}}"""))
        assertEquals(1, pl.items.size)
        assertEquals("Manusia", pl.items[0].album)
        assertEquals(SpotifyAccount.HASHES.keys, setOf("profileAttributes", "libraryV3", "fetchPlaylist", "fetchLibraryTracks"))
    }

    @Test fun spotifyLoginWebViewHelpers() {
        // Yuma's getCleanChromeUserAgent: drop the WebView markers Google sign-in rejects.
        val wv = "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36", cleanUserAgent(wv))
        assertEquals("https://accounts.spotify.com", spotifyCookieOrigin("https://accounts.spotify.com/en/login?continue=x"))
        assertEquals("https://spotify.com", spotifyCookieOrigin("https://spotify.com/"))
        assertNull(spotifyCookieOrigin("https://spotify.com.evil.example/"))
        assertNull(spotifyCookieOrigin("https://accounts.google.com/"))
        assertTrue(isWebViewLoadableUrl("https://accounts.google.com/o/oauth2"))
        assertTrue(isWebViewLoadableUrl("blob:https://open.spotify.com/1"))
        assertFalse(isWebViewLoadableUrl("intent://open#Intent;scheme=spotify;end"))
        assertFalse(isWebViewLoadableUrl("spotify:track:1"))
    }

    @Test fun spotifyCookieInputIsCleaned() {
        val v = "AQ" + "x".repeat(40)
        assertEquals(v, SpotifyAccount.cleanCookie(v))
        assertEquals(v, SpotifyAccount.cleanCookie("sp_dc=$v"))
        assertEquals(v, SpotifyAccount.cleanCookie("sp_t=abc; sp_dc=$v; sp_key=k"))
        assertEquals(v, SpotifyAccount.cleanCookie("  \"$v\"  "))
        assertEquals("k", SpotifyAccount.cleanCookie("sp_dc=$v; sp_key=k", "sp_key"))
    }

    @Test fun spotifyRestProfileFallback() {
        val me = SpotifyAccount.parseRestProfile(JSONObject("""{"id":"abc","display_name":"Yena","images":[{"url":"a","width":64}]}"""))
        assertEquals("abc", me.id)
        assertEquals("Yena", me.name)
        assertEquals("a", me.avatarUrl)
        assertEquals("abc", SpotifyAccount.parseRestProfile(JSONObject("""{"id":"abc","display_name":null,"images":[]}""")).name)
    }

    @Test fun speedLabels() {
        assertEquals("1×", speedLabel(1f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("0.5×", speedLabel(0.5f))
        assertEquals("2×", speedLabel(2f))
    }
}
