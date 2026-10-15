package com.vyora.music.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * YouTube Music search categories. [ytFilter] is NewPipe's content-filter id
 * (YoutubeSearchQueryHandlerFactory.MUSIC_*). Using these instead of a plain
 * search keeps podcasts, live streams and random videos out of the results and
 * gives every category its own complete, pageable list.
 */
enum class SearchFilter(val ytFilter: String) {
    SONGS("music_songs"),
    ARTISTS("music_artists"),
    ALBUMS("music_albums"),
    PLAYLISTS("music_playlists"),
    /** Music videos, covers, live versions: many songs only exist here. */
    VIDEOS("music_videos")
}

/** One page of results plus the cursor for the next one (null = no more). */
data class ResultPage(
    val items: List<SimpleItem>,
    val nextPage: Page? = null,
    /**
     * YouTube's spelling suggestion for the query, if any. When [corrected] is true the
     * results are already for the suggestion ("Showing results for ..."), otherwise it is
     * only a "Did you mean ...?" hint.
     */
    val suggestion: String? = null,
    val corrected: Boolean = false,
)

data class PlaylistDetail(
    val title: String,
    val owner: String,
    val thumbnail: String?,
    val totalSongs: Long,
    val page: ResultPage
)

object YouTubeClient {

    private const val TAG = "VYORA-YouTube"

    init {
        try {
            NewPipe.init(OkHttpDownloader())
            Log.d(TAG, "NewPipe initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init NewPipe", e)
        }
    }

    // ---------------------------------------------------------------- search

    /** First page when [page] is null, otherwise the page [page] points to. */
    suspend fun search(query: String, filter: SearchFilter, page: Page? = null): Result<ResultPage> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(searchBlocking(query, filter, page))
            } catch (e: Exception) {
                Log.e(TAG, "Search failed ($filter, page=${page != null})", e)
                Result.failure(e)
            }
        }

    private fun searchBlocking(query: String, filter: SearchFilter, page: Page?): ResultPage {
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter.ytFilter), "")
        val rawItems: List<InfoItem>
        val next: Page?
        var suggestion: String? = null
        var corrected = false
        if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            rawItems = info.relatedItems
            next = info.nextPage
            suggestion = info.searchSuggestion?.trim()?.takeIf { it.isNotBlank() && !it.equals(query.trim(), ignoreCase = true) }
            corrected = suggestion != null && info.isCorrectedSearch
        } else {
            val more = SearchInfo.getMoreItems(service, handler, page)
            rawItems = more.items
            next = more.nextPage
        }
        val items = rawItems.mapNotNull { toSimpleItem(it, filter) }.distinctBy { it.id }
        Log.d(TAG, "Search '$query' [$filter]: ${items.size} items, more=${next != null}")
        return ResultPage(items, next, suggestion, corrected)
    }

    private fun toSimpleItem(item: InfoItem, filter: SearchFilter): SimpleItem? = when (item) {
        is StreamInfoItem -> streamToSong(item, isVideo = videoFlag(item, filter))
        is ChannelInfoItem -> SimpleItem(
            id = item.url.substringAfterLast("/"),
            title = item.name,
            // Localised in the UI (ItemRow) from [SimpleItem.count].
            artist = "",
            thumbnail = artUrl(bestThumb(item.thumbnails), 544),
            url = item.url,
            type = ItemType.ARTIST,
            count = item.subscriberCount,
        )
        is PlaylistInfoItem -> {
            val id = item.url.substringAfter("list=", "").substringBefore("&")
            if (id.isBlank()) null else SimpleItem(
                id = id,
                title = item.name,
                artist = item.uploaderName?.takeIf { it.isNotBlank() }.orEmpty(),
                thumbnail = artUrl(bestThumb(item.thumbnails), 544),
                url = item.url,
                type = if (filter == SearchFilter.ALBUMS || isAlbumId(id)) ItemType.ALBUM else ItemType.PLAYLIST,
                count = item.streamCount,
                artistId = channelIdOf(item.uploaderUrl),
            )
        }
        else -> null
    }

    /**
     * [forceThumb] replaces the track's own picture: album tracks come with 16:9 video
     * frames, but the player should show the square album cover.
     */
    private fun streamToSong(
        item: StreamInfoItem,
        fallbackThumb: String? = null,
        forceThumb: String? = null,
        isVideo: Boolean = false,
    ): SimpleItem? {
        val id = extractVideoId(item.url)
        if (id.isBlank()) return null
        return SimpleItem(
            id = id,
            title = item.name,
            // Blank when unknown: rows simply hide the line instead of showing English "Unknown".
            artist = item.uploaderName?.removeSuffix(YouTubeConfig.TOPIC_SUFFIX)?.takeIf { it.isNotBlank() }.orEmpty(),
            thumbnail = forceThumb ?: artUrl(bestThumb(item.thumbnails), 544) ?: fallbackThumb,
            url = item.url,
            duration = item.duration.coerceAtLeast(0L),
            type = ItemType.SONG,
            artistId = channelIdOf(item.uploaderUrl),
            isVideo = isVideo,
        )
    }

    /**
     * Music videos get moving artwork. The "Videos" search tab is videos by definition and
     * "Songs" never are; elsewhere (radio, playlists) a track is a video when it was not
     * uploaded by an auto-generated "- Topic" channel and its picture is a video frame.
     */
    private fun videoFlag(item: StreamInfoItem, filter: SearchFilter?): Boolean = when (filter) {
        SearchFilter.VIDEOS -> true
        SearchFilter.SONGS -> false
        else -> looksLikeVideo(item)
    }

    private fun looksLikeVideo(item: StreamInfoItem): Boolean {
        val uploader = item.uploaderName.orEmpty()
        if (uploader.endsWith(YouTubeConfig.TOPIC_SUFFIX)) return false
        return item.thumbnails.any { it.url.contains(YouTubeConfig.VIDEO_THUMB_HOST) }
    }

    /**
     * Largest picture up to [MAX_THUMB_PX] tall. Video frames (i.ytimg.com) otherwise come
     * as 1280x720 "maxres" JPEGs, far bigger than a 48dp row or a 260dp tile ever needs,
     * and every one of them lands in the image disk cache. Music covers are resized
     * separately by [artUrl].
     */
    private fun bestThumb(list: List<org.schabi.newpipe.extractor.Image>): String? =
        list.filter { it.height > 0 }.let { known ->
            if (known.isEmpty()) list.firstOrNull()?.url // sizes unknown (-1): take what's there
            else (known.filter { it.height <= MAX_THUMB_PX }.maxByOrNull { it.height } ?: known.minByOrNull { it.height })?.url
        }

    private const val MAX_THUMB_PX = 480

    private val sizedArt = Regex("=w\\d+-h\\d+")
    private val squareArt = Regex("=s\\d+(?=[-?]|$)")

    /**
     * YouTube Music hands out 60/120 px artwork; Google's image CDN can resize on the fly,
     * so ask for [px] instead. Lists get 544, the player/album header ask for more.
     * Video thumbnails (i.ytimg.com) are left alone.
     */
    fun artUrl(url: String?, px: Int): String? {
        if (url.isNullOrBlank()) return url
        if (!url.contains("googleusercontent.com") && !url.contains("ggpht.com")) return url
        return when {
            sizedArt.containsMatchIn(url) -> url.replace(sizedArt, "=w$px-h$px")
            squareArt.containsMatchIn(url) -> url.replace(squareArt, "=s$px")
            !url.contains('=') -> "$url=w$px-h$px"
            else -> url
        }
    }

    /** "Album – Manusia" / "EP – X" / "Single – X" -> the real title. */
    private fun cleanAlbumTitle(name: String): String =
        name.replace(Regex("^(Album|EP|Single)\\s+[–-]\\s+"), "")

    // -------------------------------------------------------------- playlists

    fun playlistUrl(id: String) = "${YouTubeConfig.WEB_ORIGIN}/playlist?list=$id"

    /** YouTube Music album ids ("OLAK5uy_..."). */
    fun isAlbumId(id: String) = id.startsWith(YouTubeConfig.ALBUM_ID_PREFIX)

    /** Albums and playlists share the same extractor; [id] is the `list=` value. */
    suspend fun getPlaylist(id: String): Result<PlaylistDetail> = withContext(Dispatchers.IO) {
        try {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl(id))
            val cover = artUrl(bestThumb(info.thumbnails), 1080)
            val isAlbum = isAlbumId(id)
            val streams = info.relatedItems.filterIsInstance<StreamInfoItem>()
            val songs = streams
                .mapNotNull { streamToSong(it, cover, forceThumb = if (isAlbum) cover else null, isVideo = !isAlbum && looksLikeVideo(it)) }
                .distinctBy { it.id }
            // Album pages come without an uploader; the artist is whoever made most tracks.
            val owner = info.uploaderName?.takeIf { it.isNotBlank() }
                ?: songs.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            Result.success(
                PlaylistDetail(
                    title = if (isAlbum) cleanAlbumTitle(info.name) else info.name,
                    owner = owner,
                    thumbnail = cover,
                    totalSongs = info.streamCount,
                    page = ResultPage(songs, info.nextPage)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Get playlist failed for $id", e)
            Result.failure(e)
        }
    }

    suspend fun getPlaylistMore(id: String, page: Page, fallbackThumb: String?): Result<ResultPage> =
        withContext(Dispatchers.IO) {
            try {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, playlistUrl(id), page)
                val songs = more.items.filterIsInstance<StreamInfoItem>()
                    .mapNotNull {
                        val album = isAlbumId(id)
                        streamToSong(it, fallbackThumb, forceThumb = if (album) fallbackThumb else null, isVideo = !album && looksLikeVideo(it))
                    }
                    .distinctBy { it.id }
                Result.success(ResultPage(songs, more.nextPage))
            } catch (e: Exception) {
                Log.e(TAG, "Get more playlist items failed for $id", e)
                Result.failure(e)
            }
        }

    /** "https://www.youtube.com/channel/UC..." -> "UC...". */
    fun channelIdOf(url: String?): String? =
        url?.substringAfter("/channel/", "")?.substringBefore('/')?.substringBefore('?')?.takeIf { it.startsWith("UC") }

    // ------------------------------------------------------------------- radio

    /**
     * Songs similar to [videoId]: YouTube Music's own "radio" mix for the track (the RDAMVM
     * list behind "Start radio"). Used by Autoplay and "Create Station". The seed itself is
     * dropped. Mix thumbnails are 16:9 video frames; the square crop shows the cover.
     */
    suspend fun radio(videoId: String): Result<List<SimpleItem>> = withContext(Dispatchers.IO) {
        try {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, SimpleItem.watchUrl(videoId) + "&list=" + YouTubeConfig.RADIO_LIST_PREFIX + videoId)
            val songs = info.relatedItems.filterIsInstance<StreamInfoItem>()
                .mapNotNull { streamToSong(it, isVideo = looksLikeVideo(it)) }
                .filter { it.id != videoId && it.duration in 1..MAX_RADIO_SONG_SEC }
                .distinctBy { it.id }
            Result.success(songs)
        } catch (e: Exception) {
            Log.e(TAG, "Radio failed for $videoId", e)
            Result.failure(e)
        }
    }

    /** Hour-long mixes and live streams make poor Autoplay picks. */
    private const val MAX_RADIO_SONG_SEC = 15L * 60

    private fun extractVideoId(url: String): String {
        return when {
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            url.contains("/shorts/") -> url.substringAfterLast("/")
            url.contains("youtu.be/") -> url.substringAfterLast("/")
            else -> url.substringAfterLast("/")
        }.takeIf { it.length >= 11 } ?: ""
    }
}
