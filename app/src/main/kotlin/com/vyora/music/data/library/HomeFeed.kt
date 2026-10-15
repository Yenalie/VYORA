package com.vyora.music.data.library

import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SearchFilter
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A Top Picks card: the song plus why it is there ("Because you listened to Tulus"). */
data class TopPick(val item: SimpleItem, val becauseOf: String?)

/** "More from Tulus" style shelf built around one of the user's artists. */
data class ArtistShelf(val artist: String, val songs: List<SimpleItem>)

data class HomeContent(
    val topPicks: List<TopPick>,
    val topSongs: List<SimpleItem>,
    val artistShelves: List<ArtistShelf>,
) {
    val isEmpty get() = topPicks.isEmpty() && topSongs.isEmpty() && artistShelves.isEmpty()
}

/**
 * Builds Home from what the user actually listens to, like Apple Music's Home:
 *  - the artists they play and favorite most become the seeds;
 *  - Top Picks mixes new songs from those artists with what is popular right now;
 *  - each seed artist gets a "More from …" shelf;
 *  - Top Songs follows a chart query in the app language (no hard-coded year, so it
 *    never goes stale).
 * The result is kept for the session so switching tabs does not refetch it.
 */
object HomeFeed {
    private var cacheKey: String? = null
    private var cache: HomeContent? = null

    /** Artists weighted by how often they appear: favorites count double. */
    fun seedArtists(recent: List<SimpleItem>, liked: List<SimpleItem>, max: Int = 3): List<String> {
        val score = LinkedHashMap<String, Int>()
        fun add(list: List<SimpleItem>, weight: Int) = list.forEach { item ->
            val name = primaryArtist(item.artist)
            if (name.isNotBlank()) score[name] = (score[name] ?: 0) + weight
        }
        add(liked, 2)
        add(recent, 1)
        return score.entries.sortedByDescending { it.value }.map { it.key }.take(max)
    }

    fun cached(key: String): HomeContent? = cache?.takeIf { cacheKey == key }

    fun invalidate() {
        cacheKey = null
        cache = null
    }

    suspend fun load(chartQuery: String, recent: List<SimpleItem>, liked: List<SimpleItem>): Result<HomeContent> = coroutineScope {
        val seeds = seedArtists(recent, liked)
        val key = chartQuery + "|" + seeds.joinToString(",")
        cached(key)?.let { return@coroutineScope Result.success(it) }

        val heard = (recent + liked).map { it.id }.toSet()
        val chartJob = async { YouTubeClient.search(chartQuery, SearchFilter.SONGS) }
        val seedJobs = seeds.map { artist -> artist to async { YouTubeClient.search(artist, SearchFilter.SONGS) } }

        val chart = chartJob.await().getOrNull()?.items.orEmpty().filter { it.type == ItemType.SONG }.distinctBy { it.id }
        val shelves = seedJobs.mapNotNull { (artist, job) ->
            // The channel the user actually listened to, so a same-name artist stays out.
            val channel = (recent + liked).firstOrNull { primaryArtist(it.artist).equals(artist, ignoreCase = true) && it.artistId != null }?.artistId
            val songs = job.await().getOrNull()?.items.orEmpty()
                .filter { it.type == ItemType.SONG && primaryArtist(it.artist).equals(artist, ignoreCase = true) }
                .filter { channel == null || it.artistId == null || it.artistId == channel }
                .distinctBy { it.id }
            if (songs.isEmpty()) null else ArtistShelf(artist, songs.take(15))
        }
        if (chart.isEmpty() && shelves.isEmpty()) return@coroutineScope Result.failure(IllegalStateException("No home content"))

        val content = HomeContent(
            topPicks = mixTopPicks(shelves, chart, heard),
            topSongs = chart.take(20),
            artistShelves = shelves,
        )
        cacheKey = key
        cache = content
        Result.success(content)
    }

    /**
     * Two unheard songs per seed artist (round-robin, so one artist does not fill the row),
     * then the chart fills the rest. Six cards, no duplicates.
     */
    fun mixTopPicks(shelves: List<ArtistShelf>, chart: List<SimpleItem>, heard: Set<String>, size: Int = 6): List<TopPick> {
        val picks = mutableListOf<TopPick>()
        val used = mutableSetOf<String>()
        val pools = shelves.map { shelf -> shelf.artist to shelf.songs.filter { it.id !in heard }.ifEmpty { shelf.songs }.take(2) }
        for (round in 0 until 2) {
            for ((artist, pool) in pools) {
                val song = pool.getOrNull(round) ?: continue
                if (picks.size < size && used.add(song.id)) picks += TopPick(song, artist)
            }
        }
        for (song in chart) {
            if (picks.size >= size) break
            if (used.add(song.id)) picks += TopPick(song, null)
        }
        return picks
    }
}
