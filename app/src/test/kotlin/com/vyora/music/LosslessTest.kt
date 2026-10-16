package com.vyora.music

import com.vyora.music.data.local.AudioFormatInfo
import com.vyora.music.data.local.AudioHeaders
import com.vyora.music.data.local.AudioQuality
import com.vyora.music.data.local.AudioTier
import com.vyora.music.data.local.LocalTrack
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.screens.LocalFilter
import com.vyora.music.ui.screens.filteredBy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessTest {
    @Test fun tiersFollowAppleMusicDefinitions() {
        assertEquals(AudioTier.LOSSLESS, AudioQuality.tier("audio/flac", 44_100, 16))
        assertEquals(AudioTier.LOSSLESS, AudioQuality.tier("audio/flac", 48_000, 24))
        assertEquals(AudioTier.HI_RES_LOSSLESS, AudioQuality.tier("audio/flac", 96_000, 24))
        assertEquals(AudioTier.HI_RES_LOSSLESS, AudioQuality.tier("audio/alac", 192_000, 24))
        assertEquals(AudioTier.LOSSLESS, AudioQuality.tier("audio/x-wav", 0, 0))
        assertEquals(AudioTier.LOSSY, AudioQuality.tier("audio/opus", 48_000, 0))
        assertEquals(AudioTier.LOSSY, AudioQuality.tier("audio/mp4a-latm", 96_000, 0))
        assertEquals(AudioTier.LOSSY, AudioQuality.tier(null, 0, 0))
    }

    @Test fun labelsReadLikeTheBadge() {
        assertEquals("FLAC", AudioQuality.codecLabel("audio/flac"))
        assertEquals("ALAC", AudioQuality.codecLabel("audio/alac"))
        assertEquals("Opus", AudioQuality.codecLabel("audio/opus"))
        assertEquals("AAC", AudioQuality.codecLabel("audio/mp4a-latm"))
        assertEquals("MP3", AudioQuality.codecLabel("audio/mpeg"))
        assertEquals("44.1 kHz", AudioQuality.kiloHertz(44_100))
        assertEquals("96 kHz", AudioQuality.kiloHertz(96_000))
        assertEquals("24-bit / 96 kHz", AudioFormatInfo("audio/flac", 96_000, 24).resolutionLabel())
        assertEquals("Opus · 160 kbps", AudioFormatInfo("audio/opus", 48_000, 0, 160_000).resolutionLabel())
    }

    @Test fun flacStreamInfoIsDecoded() {
        val head = flacHeader(sampleRate = 96_000, channels = 2, bits = 24)
        val f = requireNotNull(AudioHeaders.parseFlac(head))
        assertEquals(96_000, f.sampleRateHz)
        assertEquals(2, f.channels)
        assertEquals(24, f.bitDepth)
        assertEquals(AudioTier.HI_RES_LOSSLESS, f.tier)
        val cd = requireNotNull(AudioHeaders.parseFlac(flacHeader(44_100, 2, 16)))
        assertEquals(44_100, cd.sampleRateHz)
        assertEquals(16, cd.bitDepth)
        assertNull(AudioHeaders.parseFlac("ID3xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".toByteArray()))
        assertNull(AudioHeaders.parseFlac(head.copyOf(20)))
    }

    @Test fun wavFmtChunkIsDecoded() {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(48_000).putInt(48_000 * 6).putShort(6).putShort(24)
        b.put("data".toByteArray()).putInt(0)
        val f = requireNotNull(AudioHeaders.parseWav(b.array()))
        assertEquals(48_000, f.sampleRateHz)
        assertEquals(24, f.bitDepth)
        assertEquals(2, f.channels)
        assertEquals(AudioTier.LOSSLESS, f.tier)
        assertNull(AudioHeaders.parseWav(ByteArray(44)))
    }

    @Test fun localIdsMapToMediaStoreAndNeverCollideWithYouTube() {
        val id = SimpleItem.localId(42)
        assertEquals("local:42", id)
        assertTrue(SimpleItem.isLocalId(id))
        assertFalse(SimpleItem.isLocalId("dQw4w9WgXcQ"))
        assertEquals("content://media/external/audio/media/42", SimpleItem.playbackUrl(id))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", SimpleItem.playbackUrl("dQw4w9WgXcQ"))
    }

    @Test fun filtersPickTheRightTiers() {
        fun t(id: Long, mime: String, rate: Int) = LocalTrack(
            SimpleItem(SimpleItem.localId(id), "t$id", "a", null, "", 200, "song"), "", 0, AudioFormatInfo(mime, rate, 24), 0,
        )
        val tracks = listOf(t(1, "audio/mpeg", 44_100), t(2, "audio/flac", 44_100), t(3, "audio/flac", 96_000))
        assertEquals(3, tracks.filteredBy(LocalFilter.ALL).size)
        assertEquals(listOf(2L, 3L), tracks.filteredBy(LocalFilter.LOSSLESS).map { it.item.id.removePrefix("local:").toLong() })
        assertEquals(listOf("local:3"), tracks.filteredBy(LocalFilter.HI_RES).map { it.item.id })
    }

    private fun flacHeader(sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val out = ByteArray(8 + 34)
        "fLaC".toByteArray().copyInto(out)
        out[4] = 0x80.toByte() // last-metadata-block flag + type 0 (STREAMINFO)
        out[7] = 34
        val si = 8
        out[si + 10] = (sampleRate ushr 12).toByte()
        out[si + 11] = (sampleRate ushr 4).toByte()
        out[si + 12] = (((sampleRate and 0xF) shl 4) or ((channels - 1) shl 1) or ((bits - 1) ushr 4)).toByte()
        out[si + 13] = (((bits - 1) and 0xF) shl 4).toByte()
        return out
    }
}
