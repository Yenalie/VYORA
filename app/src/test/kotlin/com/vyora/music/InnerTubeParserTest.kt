package com.vyora.music

import com.vyora.music.data.account.InnerTubeAuth
import com.vyora.music.data.account.InnerTubeParser
import com.vyora.music.data.youtube.ItemType
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeParserTest {
    private fun run(text: String, browseId: String? = null, pageType: String? = null) = JSONObject().put("text", text).apply {
        if (browseId != null) put(
            "navigationEndpoint",
            JSONObject().put(
                "browseEndpoint",
                JSONObject().put("browseId", browseId).apply {
                    if (pageType != null) put(
                        "browseEndpointContextSupportedConfigs",
                        JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", pageType)),
                    )
                },
            ),
        )
    }

    private fun songRow(id: String, title: String, videoType: String, setId: String? = null) = JSONObject("""
        {"musicResponsiveListItemRenderer":{
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3/small"},{"url":"https://lh3/big"}]}}},
          "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"$id",
            "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$videoType"}}}}}}}},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[]}}}
          ],
          "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"4:02"}]}}}]
          ${if (setId != null) ""","playlistItemData":{"videoId":"$id","playlistSetVideoId":"$setId"}""" else ""}
        }}""").also {
        it.getJSONObject("musicResponsiveListItemRenderer").getJSONArray("flexColumns").getJSONObject(1)
            .getJSONObject("musicResponsiveListItemFlexColumnRenderer").getJSONObject("text").getJSONArray("runs")
            .put(run("Tulus", "UC_DHlXllTSMB8pTC38_leFg", "MUSIC_PAGE_TYPE_ARTIST")).put(run(" • ")).put(run("Manusia", "MPREb_1", "MUSIC_PAGE_TYPE_ALBUM"))
    }

    @Test fun likedSongsPageWithContinuation() {
        val root = JSONObject().put(
            "contents", JSONObject().put("sectionListRenderer", JSONObject().put("contents", org.json.JSONArray().put(
                JSONObject().put("musicShelfRenderer", JSONObject()
                    .put("contents", org.json.JSONArray()
                        .put(songRow("abcdefghijk", "Hati-Hati di Jalan", "MUSIC_VIDEO_TYPE_ATV"))
                        .put(songRow("videoidxxxx", "Interaksi", "MUSIC_VIDEO_TYPE_OMV"))
                        .put(JSONObject("""{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}""")))
                ),
            ))),
        )
        val page = InnerTubeParser.songs(root)
        assertEquals("NEXT", page.continuation)
        assertEquals(listOf("abcdefghijk", "videoidxxxx"), page.items.map { it.item.id })
        val first = page.items[0].item
        assertEquals("Hati-Hati di Jalan", first.title)
        assertEquals("Tulus", first.artist)
        assertEquals("UC_DHlXllTSMB8pTC38_leFg", first.artistId)
        assertEquals("https://lh3/big", first.thumbnail)
        assertEquals(242_000L, first.duration)
        assertFalse(first.isVideo)
        assertTrue(page.items[1].item.isVideo)
    }

    @Test fun playlistPageReadsOnlyItsOwnShelfAndHandles() {
        val root = JSONObject().put("contents", org.json.JSONArray()
            .put(JSONObject().put("musicEditablePlaylistDetailHeaderRenderer", JSONObject().put("header",
                JSONObject().put("musicResponsiveHeaderRenderer", JSONObject().put("title", JSONObject().put("runs", org.json.JSONArray().put(run("Jalan-jalan"))))))))
            .put(JSONObject().put("musicPlaylistShelfRenderer", JSONObject().put("contents", org.json.JSONArray().put(songRow("abcdefghijk", "Hati", "MUSIC_VIDEO_TYPE_ATV", setId = "SET1")))))
            .put(JSONObject().put("musicShelfRenderer", JSONObject().put("contents", org.json.JSONArray().put(songRow("suggestedxx", "Saran", "MUSIC_VIDEO_TYPE_ATV"))))))
        val page = InnerTubeParser.songs(root)
        assertEquals(listOf("abcdefghijk"), page.items.map { it.item.id })
        assertEquals("SET1", page.items[0].setVideoId)
        assertTrue(InnerTubeParser.isEditable(root))
        assertEquals("Jalan-jalan", InnerTubeParser.playlistTitle(root))
        assertNull(page.continuation)
    }

    @Test fun libraryCardsAlbumsPlaylistsAndSkips() {
        fun card(title: String, browseId: String?, pageType: String?, overlayPlaylist: String? = null) = JSONObject().put("musicTwoRowItemRenderer", JSONObject().apply {
            put("title", JSONObject().put("runs", org.json.JSONArray().put(run(title))))
            put("subtitle", JSONObject().put("runs", org.json.JSONArray().put(run("Album")).put(run(" • ")).put(run("Tulus", "UC1", "MUSIC_PAGE_TYPE_ARTIST"))))
            if (browseId != null) put("navigationEndpoint", run("", browseId, pageType).getJSONObject("navigationEndpoint"))
            else put("navigationEndpoint", JSONObject().put("createPlaylistServiceEndpoint", JSONObject()))
            if (overlayPlaylist != null) put("thumbnailOverlay", JSONObject("""{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"$overlayPlaylist"}}}}}}"""))
        })
        val grid = JSONObject().put("items", org.json.JSONArray()
            .put(card("New playlist", null, null))
            .put(card("Liked music", "VLLM", "MUSIC_PAGE_TYPE_PLAYLIST"))
            .put(card("Jalan", "VLPL123", "MUSIC_PAGE_TYPE_PLAYLIST"))
            .put(card("Manusia", "MPREb_1", "MUSIC_PAGE_TYPE_ALBUM", overlayPlaylist = "OLAK5uy_m"))
            .put(card("No list", "MPREb_2", "MUSIC_PAGE_TYPE_ALBUM")))
        val related = JSONObject().put("musicCarouselShelfRenderer", JSONObject().put("contents", org.json.JSONArray().put(card("Related", "VLPLzzz", "MUSIC_PAGE_TYPE_PLAYLIST"))))
        val root = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("gridRenderer", grid)).put(related))
        val items = InnerTubeParser.collections(root).items
        assertEquals(listOf("PL123", "OLAK5uy_m"), items.map { it.item.id })
        assertEquals(ItemType.PLAYLIST, items[0].item.type)
        assertEquals(ItemType.ALBUM, items[1].item.type)
        assertTrue(items[1].isAlbum)
        assertEquals("Tulus", items[1].item.artist)
    }

    @Test fun accountHeader() {
        val root = JSONObject("""{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{
            "accountName":{"runs":[{"text":"Yena"}]},"channelHandle":{"runs":[{"text":"@yena"}]},
            "accountPhoto":{"thumbnails":[{"url":"https://yt3/a=s48"},{"url":"https://yt3/a=s88"}]}}}}}}}]}""")
        val account = InnerTubeParser.account(root)!!
        assertEquals("Yena", account.name)
        assertEquals("@yena", account.handle)
        assertEquals("https://yt3/a=s88", account.photo)
        assertNull(InnerTubeParser.account(JSONObject()))
    }

    @Test fun durations() {
        assertEquals(242_000L, InnerTubeParser.parseDuration("4:02"))
        assertEquals(3_723_000L, InnerTubeParser.parseDuration("1:02:03"))
        assertEquals(262_000L, InnerTubeParser.parseDuration("4.22"))
        assertNull(InnerTubeParser.parseDuration("Live"))
    }

    @Test fun sapisidHashSignature() {
        val cookie = "SID=a; SAPISID=secret/xyz; HSID=b"
        val expected = MessageDigest.getInstance("SHA-1").digest("1700000000 secret/xyz https://music.youtube.com".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("SAPISIDHASH 1700000000_$expected", InnerTubeAuth.authorization(cookie, nowSeconds = 1_700_000_000L))
        assertTrue(InnerTubeAuth.hasSession("__Secure-3PAPISID=q"))
        assertFalse(InnerTubeAuth.hasSession("SID=a; HSID=b"))
        assertNull(InnerTubeAuth.authorization("SID=a"))
    }
}
