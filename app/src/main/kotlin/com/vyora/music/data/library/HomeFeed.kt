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

/** The personal playlists of the "Made for You" row, like Apple Music's Favourites / Discovery mixes. */
enum class MixKind { FAVORITES, DISCOVERY, REDISCOVER }

data class PersonalMix(val kind: MixKind, val songs: List<SimpleItem>)

/** An endless radio built from one seed song ("Stations for You"). [artist] is null for the Discovery Station. */
data class Station(val artist: String?, val seed: SimpleItem)

data class HomeContent(
    val topPicks: List<TopPick>,
    val topSongs: List<SimpleItem>,
    val artistShelves: List<ArtistShelf>,
    val mixes: List<SongMix> = emptyList(),
    /** Liked songs that have not come round in a while. */
    val rediscover: List<SimpleItem> = emptyList(),
    val madeForYou: List<PersonalMix> = emptyList(),
    val stations: List<Station> = emptyList(),
    /** Albums by the artists the listener plays most. */
    val albums: List<SimpleItem> = emptyList(),
    val musicVideos: List<SimpleItem> = emptyList(),
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
        val albumJobs = seeds.take(3).map { artist -> artist to async { YouTubeClient.search(artist, SearchFilter.ALBUMS) } }
        // Official videos from the artists in rotation; a chart query mostly finds hour-long compilations.
        val videoJobs = seeds.take(3).map { artist -> artist to async { YouTubeClient.search("$artist official music video", SearchFilter.VIDEOS) } }

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

        val albums = interleave(albumJobs.map { (artist, job) ->
            job.await().getOrNull()?.items.orEmpty()
                .filter { it.type == ItemType.ALBUM && primaryArtist(it.artist).equals(artist, ignoreCase = true) }
                .distinctBy { it.id }
                .take(6)
        }).take(12)
        val videos = interleave(videoJobs.map { (artist, job) ->
            job.await().getOrNull()?.items.orEmpty()
                .filter { it.type == ItemType.SONG && (it.duration in 1..MAX_VIDEO_SECONDS || it.duration <= 0) }
                .filter { primaryArtist(it.artist).contains(artist, ignoreCase = true) || it.title.contains(artist, ignoreCase = true) }
                .take(5)
        }).distinctBy { it.id }.take(12)
        val rediscover = rediscover(recent, liked)

        val content = HomeContent(
            topPicks = mixTopPicks(shelves + mixes.map { ArtistShelf(it.seed.title, it.songs) }, chart, heard, size = 8),
            topSongs = chart.take(20),
            artistShelves = shelves,
            mixes = mixes,
            rediscover = rediscover,
            madeForYou = madeForYou(liked, rediscover, discovery(mixes, shelves, chart, heard)),
            stations = stations(shelves, chart, heard),
            albums = albums,
            musicVideos = videos,
        )
        cacheKey = key
        cache = content
        Result.success(content)
    }

    /** Songs the listener has not heard yet, drawn from every station and shelf, two per artist at most. */
    fun discovery(mixes: List<SongMix>, shelves: List<ArtistShelf>, chart: List<SimpleItem>, heard: Set<String>): List<SimpleItem> {
        val pool = interleave(mixes.map { it.songs } + shelves.map { it.songs } + listOf(chart))
        return Recommend.extend(emptyList(), pool, limit = 25, exclude = heard)
    }

    fun madeForYou(liked: List<SimpleItem>, rediscover: List<SimpleItem>, discovery: List<SimpleItem>): List<PersonalMix> = buildList {
        if (liked.size >= MIN_MIX) add(PersonalMix(MixKind.FAVORITES, liked.take(50)))
        if (discovery.size >= MIN_MIX) add(PersonalMix(MixKind.DISCOVERY, discovery))
        if (rediscover.size >= MIN_MIX) add(PersonalMix(MixKind.REDISCOVER, rediscover))
    }

    /** One station per favourite artist, then a Discovery Station seeded by something not heard yet. */
    fun stations(shelves: List<ArtistShelf>, chart: List<SimpleItem>, heard: Set<String>): List<Station> = buildList {
        shelves.forEach { shelf -> shelf.songs.firstOrNull { it.isYouTube }?.let { add(Station(shelf.artist, it)) } }
        val fresh = (shelves.flatMap { it.songs } + chart).firstOrNull { it.isYouTube && it.id !in heard && none { s -> s.seed.id == it.id } }
        if (fresh != null) add(Station(null, fresh))
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = mutableListOf<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
        return out
    }

    private const val MIN_MIX = 5
    private const val MAX_VIDEO_SECONDS = 12 * 60L

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
