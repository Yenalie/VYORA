package com.vyora.music

import com.vyora.music.data.library.ArtistShelf
import com.vyora.music.data.library.HomeFeed
import com.vyora.music.data.library.primaryArtist
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.screens.creditsArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedTest {
    private fun song(id: String, artist: String) = SimpleItem(id, "t$id", artist, null, "")

    @Test fun primaryArtistTakesTheFirstCredit() {
        assertEquals("Tulus", primaryArtist("Tulus, Gita Gutawa"))
        assertEquals("Nadin Amizah", primaryArtist("Nadin Amizah & Bilal Indrajaya"))
        assertEquals("Hindia", primaryArtist("Hindia feat. Rara Sekar"))
    }

    @Test fun favoritesWeighDouble() {
        val recent = listOf(song("1", "A"), song("2", "A"), song("3", "B"))
        val liked = listOf(song("4", "B"), song("5", "C"))
        assertEquals("B", HomeFeed.seedArtists(recent, liked).first())
    }

    @Test fun topPicksAlternateArtistsSkipHeardAndFillFromChart() {
        val shelves = listOf(
            ArtistShelf("A", listOf(song("a1", "A"), song("a2", "A"), song("a3", "A"))),
            ArtistShelf("B", listOf(song("b1", "B"), song("b2", "B"))),
        )
        val chart = listOf(song("a2", "A"), song("c1", "C"), song("c2", "C"), song("c3", "C"))
        val picks = HomeFeed.mixTopPicks(shelves, chart, heard = setOf("a1"))
        assertEquals(listOf("a2", "b1", "a3", "b2", "c1", "c2"), picks.map { it.item.id })
        assertEquals("A", picks.first().becauseOf)
        assertEquals(null, picks.last().becauseOf)
    }

    @Test fun creditsArtistMatchesWholeNamesOnly() {
        assertTrue(creditsArtist("Queen & David Bowie", "Queen"))
        assertTrue(creditsArtist("Tulus", "tulus"))
        assertFalse(creditsArtist("Queens of the Stone Age", "Queen"))
    }
}
