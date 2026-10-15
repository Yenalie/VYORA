package com.vyora.music

import com.vyora.music.data.settings.AudioQuality
import com.vyora.music.data.settings.EqualizerSettings
import com.vyora.music.data.youtube.AudioCandidate
import com.vyora.music.data.youtube.StreamSelector
import com.vyora.music.data.youtube.TrackKind
import com.vyora.music.data.youtube.VideoCandidate
import com.vyora.music.playback.audio.EqBands
import com.vyora.music.playback.audio.EqPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEngineTest {
    private fun audio(
        itag: Int, bitrate: Int, codec: String = "opus", drc: Boolean = false,
        kind: TrackKind = TrackKind.ORIGINAL, progressive: Boolean = true,
    ) = AudioCandidate(itag, "https://x/$itag", bitrate, codec, "audio/webm", drc, kind, progressive, -1)

    // ------------------------------------------------------------- stream choice

    @Test fun neverPicksStableVolumeWhenNormalExists() {
        val list = listOf(audio(251, 160_000, drc = true), audio(251, 158_000), audio(140, 130_000, "mp4a.40.2"))
        val pick = StreamSelector.pickAudio(list, AudioQuality.HIGH)!!
        assertEquals(false, pick.isDrc)
        assertEquals(158_000, pick.bitrate)
    }

    @Test fun prefersOriginalTrackOverDubs() {
        val list = listOf(audio(251, 170_000, kind = TrackKind.SECONDARY), audio(251, 150_000))
        assertEquals(TrackKind.ORIGINAL, StreamSelector.pickAudio(list, AudioQuality.HIGH)!!.trackKind)
    }

    @Test fun keepsCachedItagWhenStillValid() {
        val list = listOf(audio(251, 160_000), audio(140, 130_000, "mp4a.40.2"))
        assertEquals(140, StreamSelector.pickAudio(list, AudioQuality.HIGH, preferredItag = 140)!!.itag)
        // A cached DRC itag is not kept: the clean rendition wins.
        val drcOnlyCached = listOf(audio(250, 70_000, drc = true), audio(251, 160_000))
        assertEquals(251, StreamSelector.pickAudio(drcOnlyCached, AudioQuality.HIGH, preferredItag = 250)!!.itag)
    }

    @Test fun qualityLevels() {
        val list = listOf(audio(249, 50_000), audio(250, 70_000), audio(140, 130_000, "mp4a.40.2"), audio(251, 160_000))
        assertEquals(251, StreamSelector.pickAudio(list, AudioQuality.HIGH)!!.itag)
        assertEquals(140, StreamSelector.pickAudio(list, AudioQuality.NORMAL)!!.itag)
        assertEquals(250, StreamSelector.pickAudio(list, AudioQuality.LOW)!!.itag)
    }

    @Test fun ignoresNonProgressive() {
        assertNull(StreamSelector.pickAudio(listOf(audio(251, 160_000, progressive = false)), AudioQuality.HIGH))
    }

    @Test fun videoPrefersH264UpTo480p() {
        fun v(itag: Int, h: Int, codec: String) = VideoCandidate(itag, "https://v/$itag", h, h * 1000, codec, "video/mp4", true)
        val list = listOf(v(137, 1080, "avc1.640028"), v(244, 480, "vp9"), v(135, 480, "avc1.4d401e"), v(397, 480, "av01.0.04M.08"))
        assertEquals(135, StreamSelector.pickVideo(list)!!.itag)
    }

    // ----------------------------------------------------------------- equalizer

    @Test fun presetsHaveTenBands() {
        EqPreset.entries.forEach { assertEquals(it.name, EqBands.COUNT, it.gains.size) }
    }

    @Test fun boostsGetHeadroomSoNothingClips() {
        val s = EqualizerSettings(enabled = true, preset = EqPreset.BASS.id, bassBoost = 1f)
        val gains = EqBands.gains(s)
        val input = EqBands.inputGainDb(s, gains)
        assertTrue("input gain must go down when bands are boosted", input < 0f)
        assertTrue(gains.first() > EqPreset.BASS.gains.first())
        // Flat and no loudness: untouched.
        assertEquals(0f, EqBands.inputGainDb(EqualizerSettings(enabled = true)), 0f)
        // Loudness raises the input (the limiter keeps it from clipping).
        assertEquals(4f, EqBands.inputGainDb(EqualizerSettings(enabled = true, loudness = 4f)), 0f)
    }

    @Test fun customCurveOfWrongSizeIsFlat() {
        val s = EqualizerSettings(preset = EqPreset.CUSTOM, customGains = listOf(1f, 2f, 3f))
        assertTrue(EqBands.curve(s).all { it == 0f })
    }

    @Test fun legacyFiveBandGainsMigrate() {
        val migrated = EqBands.migrateLegacy(listOf(6f, 4f, 0f, 0f, 0f))
        assertEquals(EqBands.COUNT, migrated.size)
        assertEquals(6f, migrated[0], 0.01f) // 31 Hz below the old 60 Hz band: same value
        assertEquals(6f, migrated[1], 0.2f)  // 62 Hz ~ the old 60 Hz band
        assertEquals(0f, migrated[9], 0.01f)
    }

    @Test fun cutoffsSitBetweenCentres() {
        EqBands.CUTOFFS_HZ.forEachIndexed { i, c ->
            assertTrue(c > EqBands.FREQUENCIES_HZ[i])
            EqBands.FREQUENCIES_HZ.getOrNull(i + 1)?.let { assertTrue(c < it) }
        }
    }
}
