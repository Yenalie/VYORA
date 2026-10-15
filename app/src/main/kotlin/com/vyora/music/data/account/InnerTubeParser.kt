package com.vyora.music.data.account

import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.data.youtube.YouTubeConfig
import org.json.JSONArray
import org.json.JSONObject

/** A song as listed in a library page; [setVideoId] identifies that entry of a playlist. */
data class RemoteSong(val item: SimpleItem, val setVideoId: String?)

/** An album or playlist card from the library. [id] is the `list=` id NewPipe opens. */
data class RemoteCollection(val item: SimpleItem, val isAlbum: Boolean)

/** One page of a library list and the token for the next one. */
data class RemotePage<T>(val items: List<T>, val continuation: String?)

/**
 * Reads YouTube Music's web API responses.
 *
 * The layout of those responses moves around (shelves, grids, continuation wrappers), so
 * instead of fixed paths the parser looks for the item renderers wherever they sit and
 * reads each one defensively. Pure JSON in, data out: unit tested with sample responses.
 */
object InnerTubeParser {
    private const val LIST_ITEM = "musicResponsiveListItemRenderer"
    private const val CARD_ITEM = "musicTwoRowItemRenderer"
    private const val PLAYLIST_SHELF = "musicPlaylistShelfRenderer"
    private val SONG_CONTAINERS = listOf(
        "musicPlaylistShelfContinuation", "musicShelfRenderer", "musicShelfContinuation", "appendContinuationItemsAction",
    )
    private val COLLECTION_CONTAINERS = listOf(
        "gridRenderer", "gridContinuation", "musicShelfRenderer", "musicShelfContinuation", "appendContinuationItemsAction",
    )
    private const val PAGE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    private const val PAGE_USER = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private const val PAGE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    private const val AUDIO_ONLY_TYPE = "MUSIC_VIDEO_TYPE_ATV"
    private const val SEPARATOR = " • "
    /** "4:22", or "4.22" in locales (Indonesian) that YouTube formats with a dot. */
    private val durationText = Regex("^\\d{1,2}([:.]\\d{2}){1,2}$")

    // ---------------------------------------------------------------------------- pages

    fun songs(root: JSONObject): RemotePage<RemoteSong> {
        // A playlist page also carries suggestion and "related" shelves; its own songs sit
        // in the playlist shelf (or, on library pages, the one music shelf).
        val shelves = findAll(root, PLAYLIST_SHELF).ifEmpty { containers(root, SONG_CONTAINERS) }
        val items = shelves.flatMap { findAll(it, LIST_ITEM) }.mapNotNull(::song).distinctBy { it.item.id }
        return RemotePage(items, shelves.firstNotNullOfOrNull(::continuation))
    }

    fun collections(root: JSONObject): RemotePage<RemoteCollection> {
        val grids = containers(root, COLLECTION_CONTAINERS)
        val cards = grids.flatMap { findAll(it, CARD_ITEM) }.mapNotNull(::collectionCard)
        val rows = grids.flatMap { findAll(it, LIST_ITEM) }.mapNotNull(::collectionRow)
        return RemotePage((cards + rows).distinctBy { it.item.id }, grids.firstNotNullOfOrNull(::continuation))
    }

    /**
     * The list containers of a page or a continuation response, outermost first. Tokens and
     * items are read only inside them: the section list around them has its own token,
     * which leads to "related playlists", not the next page of this list.
     */
    private fun containers(root: JSONObject, keys: List<String>): List<JSONObject> =
        keys.flatMap { findAll(root, it) }

    /** True when the signed-in user owns the playlist (YouTube shows edit controls). */
    fun isEditable(root: JSONObject): Boolean =
        findAll(root, "musicEditablePlaylistDetailHeaderRenderer").isNotEmpty()

    /** Title of a playlist page, from whichever header it uses. */
    fun playlistTitle(root: JSONObject): String? =
        listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer")
            .asSequence()
            .flatMap { findAll(root, it).asSequence() }
            .mapNotNull { text(it.optJSONObject("title")) }
            .firstOrNull()

    fun account(root: JSONObject): YouTubeAccount? {
        val header = findAll(root, "activeAccountHeaderRenderer").firstOrNull() ?: return null
        val name = text(header.optJSONObject("accountName")) ?: return null
        val handle = text(header.optJSONObject("channelHandle")) ?: text(header.optJSONObject("email"))
        val photo = lastThumb(header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails"))
        return YouTubeAccount(name = name, handle = handle, photo = photo)
    }

    /** Id of a playlist made by `playlist/create`. */
    fun createdPlaylistId(root: JSONObject): String? = root.optString("playlistId").takeIf { it.isNotBlank() }

    /** Token for the next page of [container], in either of the formats YouTube uses. */
    internal fun continuation(container: JSONObject): String? {
        findAll(container, "continuationCommand").firstNotNullOfOrNull { it.optString("token").takeIf(String::isNotBlank) }?.let { return it }
        return findAll(container, "nextContinuationData").firstNotNullOfOrNull { it.optString("continuation").takeIf(String::isNotBlank) }
    }

    // ---------------------------------------------------------------------------- items

    internal fun song(r: JSONObject): RemoteSong? {
        val flex = r.optJSONArray("flexColumns") ?: return null
        val titleRuns = flexRuns(flex, 0)
        val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: findString(r.optJSONObject("overlay"), "videoId")
            ?: titleRuns.firstNotNullOfOrNull { findString(it.optJSONObject("navigationEndpoint"), "videoId") }
            ?: return null
        val title = titleRuns.joinToString("") { it.optString("text") }.trim().ifBlank { return null }

        val detail = flexRuns(flex, 1)
        val artistRuns = detail.filter { isArtistRun(it) }
        val artist = if (artistRuns.isNotEmpty()) artistRuns.joinToString(", ") { it.optString("text") }
        else detail.map { it.optString("text") }.takeWhile { it != SEPARATOR }.joinToString("").trim()
        val artistId = artistRuns.firstNotNullOfOrNull { browseId(it)?.takeIf { id -> id.startsWith("UC") } }

        val durationRun = fixedText(r) ?: detail.map { it.optString("text").trim() }.lastOrNull { durationText.matches(it) }
        val videoType = findString(r, "musicVideoType")
        return RemoteSong(
            item = SimpleItem(
                id = videoId,
                title = title,
                artist = artist,
                thumbnail = lastThumb(r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")),
                url = SimpleItem.watchUrl(videoId),
                duration = durationRun?.let(::parseDuration) ?: 0L,
                type = ItemType.SONG,
                artistId = artistId,
                isVideo = videoType != null && videoType != AUDIO_ONLY_TYPE,
            ),
            setVideoId = r.optJSONObject("playlistItemData")?.optString("playlistSetVideoId")?.takeIf { it.isNotBlank() },
        )
    }

    internal fun collectionCard(r: JSONObject): RemoteCollection? {
        val browse = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return null
        val title = text(r.optJSONObject("title")) ?: return null
        val subtitleRuns = runs(r.optJSONObject("subtitle"))
        val thumb = lastThumb(r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
        return collection(browse, title, subtitleRuns, thumb, r)
    }

    /** Library pages switched to list view show albums / playlists as list rows. */
    internal fun collectionRow(r: JSONObject): RemoteCollection? {
        if (r.optJSONObject("playlistItemData") != null) return null
        val browse = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return null
        val flex = r.optJSONArray("flexColumns") ?: return null
        val title = flexRuns(flex, 0).joinToString("") { it.optString("text") }.trim().ifBlank { return null }
        val thumb = lastThumb(r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
        return collection(browse, title, flexRuns(flex, 1), thumb, r)
    }

    private fun collection(browse: JSONObject, title: String, subtitleRuns: List<JSONObject>, thumb: String?, renderer: JSONObject): RemoteCollection? {
        val browseId = browse.optString("browseId").takeIf { it.isNotBlank() } ?: return null
        val pageType = findString(browse, "pageType")
        val isAlbum = pageType == PAGE_ALBUM || browseId.startsWith("MPRE")
        val listId = if (isAlbum) {
            // Albums open by their "OLAK5uy_" playlist, which only the play button carries.
            findString(renderer.optJSONObject("thumbnailOverlay"), "playlistId")
                ?: findString(renderer.optJSONObject("overlay"), "playlistId")
                ?: findString(renderer.optJSONObject("menu"), "playlistId")
                ?: return null
        } else {
            if (!browseId.startsWith(YouTubeConfig.BROWSE_PLAYLIST_PREFIX)) return null
            browseId.removePrefix(YouTubeConfig.BROWSE_PLAYLIST_PREFIX)
        }
        if (listId in YouTubeConfig.SYSTEM_PLAYLISTS) return null
        val artistRuns = subtitleRuns.filter { isArtistRun(it) }
        val subtitle = if (artistRuns.isNotEmpty()) artistRuns.joinToString(", ") { it.optString("text") }
        else subtitleRuns.map { it.optString("text") }.filter { it != SEPARATOR }.lastOrNull().orEmpty()
        return RemoteCollection(
            item = SimpleItem(
                id = listId,
                title = title,
                artist = subtitle,
                thumbnail = thumb,
                url = YouTubeClient.playlistUrl(listId),
                type = if (isAlbum) ItemType.ALBUM else ItemType.PLAYLIST,
                artistId = artistRuns.firstNotNullOfOrNull { browseId(it)?.takeIf { id -> id.startsWith("UC") } },
            ),
            isAlbum = isAlbum,
        )
    }

    // -------------------------------------------------------------------------- helpers

    private fun isArtistRun(run: JSONObject): Boolean {
        val endpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return false
        val pageType = findString(endpoint, "pageType")
        return pageType == PAGE_ARTIST || pageType == PAGE_USER || (pageType == null && endpoint.optString("browseId").startsWith("UC"))
    }

    private fun browseId(run: JSONObject): String? =
        run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.isNotBlank() }

    private fun flexRuns(flex: JSONArray, column: Int): List<JSONObject> =
        runs(flex.optJSONObject(column)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))

    private fun fixedText(r: JSONObject): String? =
        r.optJSONArray("fixedColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
            ?.let { text(it) }?.takeIf { durationText.matches(it) }

    private fun runs(text: JSONObject?): List<JSONObject> {
        val array = text?.optJSONArray("runs") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /** "runs" joined, or "simpleText". */
    internal fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        return runs(o).joinToString("") { it.optString("text") }.trim().takeIf { it.isNotBlank() }
    }

    private fun lastThumb(array: JSONArray?): String? {
        if (array == null || array.length() == 0) return null
        return array.optJSONObject(array.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
    }

    /** "3:45" / "1:02:03" / "3.45" -> milliseconds. */
    internal fun parseDuration(text: String): Long? {
        val parts = text.trim().split(':', '.').map { it.toLongOrNull() ?: return null }
        return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000L
    }

    /** First string value of [key] anywhere below [node]. */
    internal fun findString(node: Any?, key: String): String? {
        when (node) {
            is JSONObject -> {
                val direct = node.opt(key)
                if (direct is String && direct.isNotBlank()) return direct
                val keys = node.keys()
                while (keys.hasNext()) findString(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), key)?.let { return it }
        }
        return null
    }

    /** Every object stored under [key] anywhere below [node], in document order. */
    internal fun findAll(node: Any?, key: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val keys = n.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = n.opt(k)
                        if (k == key && v is JSONObject) out.add(v) else walk(v)
                    }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(node)
        return out
    }
}
