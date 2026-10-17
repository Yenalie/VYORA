package com.vyora.music.data.library

import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SearchFilter
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class TopPick(val item: SimpleItem, val becauseOf: String?)

data class ArtistShelf(val artist: String, val songs: List<SimpleItem>)

/** A station built from one song the listener played or liked ("Because you listened to …"). */
data class SongMix(val seed: SimpleItem, val songs: List<SimpleItem>)

data class HomeContent(
    val topPicks: List<TopPick>,
    val topSongs: List<SimpleItem>,
    val artistShelves: List<ArtistShelf>,
    val mixes: List<SongMix> = emptyList(),
    /** Liked songs that have not come round in a while. */
    val rediscover: List<SimpleItem> = emptyList(),
) {
    val isEmpty get() = topPicks.isEmpty() && topSongs.isEmpty() && artistShelves.isEmpty() && mixes.isEmpty()
}

object HomeFeed {
    private var cacheKey: String? = null
    private var cache: HomeContent? = null

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

    /**
     * Songs to build mixes from: the most played-recently song and a liked one by a different artist,
     * so the two stations do not sound the same.
     */
    fun mixSeeds(recent: List<SimpleItem>, liked: List<SimpleItem>, max: Int = 2): List<SimpleItem> {
        val out = mutableListOf<SimpleItem>()
        val pool = (recent.take(10) + liked.take(20)).filter { it.isYouTube && it.isSong }
        for (item in pool) {
            if (out.size >= max) break
            val artist = primaryArtist(item.artist)
            if (out.any { it.id == item.id || primaryArtist(it.artist).equals(artist, ignoreCase = true) }) continue
            out += item
        }
        return out
    }

    fun rediscover(recent: List<SimpleItem>, liked: List<SimpleItem>, size: Int = 15): List<SimpleItem> {
        val heard = recent.take(40).mapTo(HashSet()) { it.id }
        return liked.filter { it.id !in heard }.take(size)
    }

    fun cached(key: String): HomeContent? = cache?.takeIf { cacheKey == key }

    fun invalidate() {
        cacheKey = null
        cache = null
    }

    suspend fun load(chartQuery: String, recent: List<SimpleItem>, liked: List<SimpleItem>): Result<HomeContent> = coroutineScope {
        val seeds = seedArtists(recent, liked)
        val mixSeeds = mixSeeds(recent, liked)
        val key = chartQuery + "|" + seeds.joinToString(",") + "|" + mixSeeds.joinToString(",") { it.id }
        cached(key)?.let { return@coroutineScope Result.success(it) }

        val heard = (recent + liked).map { it.id }.toSet()
        val chartJob = async { YouTubeClient.search(chartQuery, SearchFilter.SONGS) }
        val seedJobs = seeds.map { artist -> artist to async { YouTubeClient.search(artist, SearchFilter.SONGS) } }
        val mixJobs = mixSeeds.map { seed -> seed to async { YouTubeClient.radio(seed.id) } }

        val chart = chartJob.await().getOrNull()?.items.orEmpty().filter { it.type == ItemType.SONG }.distinctBy { it.id }
        val shelves = seedJobs.mapNotNull { (artist, job) ->
            val channel = (recent + liked).firstOrNull { primaryArtist(it.artist).equals(artist, ignoreCase = true) && it.artistId != null }?.artistId
            val songs = job.await().getOrNull()?.items.orEmpty()
                .filter { it.type == ItemType.SONG && primaryArtist(it.artist).equals(artist, ignoreCase = true) }
                .filter { channel == null || it.artistId == null || it.artistId == channel }
                .distinctBy { it.id }
            if (songs.isEmpty()) null else ArtistShelf(artist, songs.take(15))
        }
        val mixes = mixJobs.mapNotNull { (seed, job) ->
            val radio = job.await().getOrNull().orEmpty()
            val songs = Recommend.extend(listOf(seed), radio, limit = 15, exclude = setOf(seed.id))
            if (songs.size < 4) null else SongMix(seed, songs)
        }
        if (chart.isEmpty() && shelves.isEmpty() && mixes.isEmpty()) return@coroutineScope Result.failure(IllegalStateException("No home content"))

        val content = HomeContent(
            topPicks = mixTopPicks(shelves + mixes.map { ArtistShelf(it.seed.title, it.songs) }, chart, heard, size = 8),
            topSongs = chart.take(20),
            artistShelves = shelves,
            mixes = mixes,
            rediscover = rediscover(recent, liked),
        )
        cacheKey = key
        cache = content
        Result.success(content)
    }

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
