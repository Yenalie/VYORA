package com.vyora.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.vyora.music.data.discord.DiscordPresence
import com.vyora.music.data.discord.PresenceTrack
import com.vyora.music.data.download.Downloads
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.playback.cast.CastProxy
import com.vyora.music.ui.player.LyricsCardRenderer
import com.vyora.music.ui.player.LyricsCardStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FeaturesTest {

    @Test fun rangeHeaders() {
        assertEquals(0L to null, CastProxy.parseRange("bytes=0-"))
        assertEquals(100L to 199L, CastProxy.parseRange("bytes=100-199"))
        assertEquals(5L to null, CastProxy.parseRange("Bytes=5-, 10-20"))
        assertNull(CastProxy.parseRange("bytes=-500"))
        assertNull(CastProxy.parseRange("bytes=9-3"))
        assertNull(CastProxy.parseRange("items=0-1"))
        assertNull(CastProxy.parseRange(null))
    }

    @Test fun containerSniffing() {
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val mp4 = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0, 0, 0, 0)
        assertEquals("audio/webm", CastProxy.sniff(webm))
        assertEquals("audio/mp4", CastProxy.sniff(mp4))
    }

    @Test fun downloadMetadataRoundTrip() {
        val item = SimpleItem(
            id = "dQw4w9WgXcQ", title = "Hati-Hati di Jalan", artist = "Tulus",
            thumbnail = "https://i.ytimg.com/a.jpg", url = SimpleItem.watchUrl("dQw4w9WgXcQ"),
            duration = 242, artistId = "UC123", isVideo = true,
        )
        val back = Downloads.decode(item.id, Downloads.encode(item).toByteArray())!!
        assertEquals(item.title, back.title)
        assertEquals(item.artist, back.artist)
        assertEquals(item.thumbnail, back.thumbnail)
        assertEquals(242L, back.duration)
        assertEquals("UC123", back.artistId)
        assertTrue(back.isVideo)
        assertTrue(back.isSong)
        val bare = Downloads.decode("abcdefghijk", null)!!
        assertEquals("abcdefghijk", bare.title)
        assertNull(Downloads.decode("", null))
    }

    @Test fun discordActivity() {
        val a = DiscordPresence.activity(PresenceTrack("X", "", 1_000L, 61_000L))
        assertEquals(2, a.getInt("type"))
        assertEquals("VYORA", a.getString("name"))
        assertEquals(2, a.getString("details").length)
        assertEquals("VYORA", a.getString("state"))
        assertEquals(61_000L, a.getJSONObject("timestamps").getLong("end"))
        val noEnd = DiscordPresence.activity(PresenceTrack("Judul", "Artis", 5_000L, 0L))
        assertFalse(noEnd.getJSONObject("timestamps").has("end"))
        val long = DiscordPresence.activity(PresenceTrack("a".repeat(300), "b", 0L, 0L))
        assertEquals(128, long.getString("details").length)
    }

    @Test fun lyricsCardRenders() {
        val art = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        Canvas(art).drawRect(0f, 0f, 600f, 600f, Paint().apply {
            shader = LinearGradient(0f, 0f, 600f, 600f, 0xFF2E86DE.toInt(), 0xFFE84393.toInt(), Shader.TileMode.CLAMP)
        })
        val lines = listOf("Kita adalah sisa-sisa keikhlasan", "yang tak diikhlaskan", "Bertahan demi rasa")
        val out = File(System.getProperty("vyora.cards") ?: "build/cards").apply { mkdirs() }
        for (style in LyricsCardStyle.entries) {
            val card = LyricsCardRenderer.render(art, "Hati-Hati di Jalan", "Tulus", lines, style)
            assertEquals(LyricsCardRenderer.WIDTH, card.width)
            assertEquals(LyricsCardRenderer.HEIGHT, card.height)
            File(out, "card_${style.name.lowercase()}.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val many = List(6) { "Baris lirik yang cukup panjang untuk dibungkus ke beberapa baris nomor $it" }
        val dense = LyricsCardRenderer.render(null, "Judul", "Artis", many, LyricsCardStyle.COLOR)
        File(out, "card_dense.png").outputStream().use { dense.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
