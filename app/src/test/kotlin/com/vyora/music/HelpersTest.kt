package com.vyora.music

import com.vyora.music.data.lyrics.LyricsRepository
import com.vyora.music.ui.components.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class HelpersTest {
    @Test fun lyricsTitleCleanup() {
        assertEquals("Hati-Hati di Jalan" to "Tulus", LyricsRepository.clean("Tulus - Hati-Hati di Jalan (Official Music Video)", "Tulus"))
        assertEquals("Bertaut" to "Nadin Amizah", LyricsRepository.clean("Bertaut", "Nadin Amizah - Topic"))
    }

    @Test fun durations() {
        assertEquals("3:35", formatDuration(215))
        assertEquals("1:02:05", formatDuration(3725))
        assertEquals("0:00", formatDuration(0))
    }
}
