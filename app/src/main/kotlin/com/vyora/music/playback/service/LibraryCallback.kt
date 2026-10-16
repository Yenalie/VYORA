package com.vyora.music.playback.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.vyora.music.R
import com.vyora.music.data.download.DownloadStatus
import com.vyora.music.data.download.Downloads
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.SearchFilter
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.playback.MediaItems
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The browsable library Android Auto (and other media browsers) see: favourites, recently played, playlists and
 * downloads, plus voice/text search on YouTube Music. Also restores the last queue when playback is resumed
 * from a headset button, the widget or the system media controls while the app was closed.
 */
@OptIn(UnstableApi::class)
class LibraryCallback(
    private val context: Context,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    /** Songs handed out while browsing, so a browser asking to play an id gets the full metadata back. */
    private val known = ConcurrentHashMap<String, SimpleItem>()
    private val searches = ConcurrentHashMap<String, List<SimpleItem>>()

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val extras = Bundle().apply {
            // Android Auto: show our folders as tabs, songs as a list, and offer search.
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }
        val root = folder(ROOT, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val children: List<MediaItem> = when {
            parentId == ROOT -> listOf(
                folder(LIKED, context.getString(R.string.auto_liked), MediaMetadata.MEDIA_TYPE_PLAYLIST),
                folder(RECENT, context.getString(R.string.auto_recent), MediaMetadata.MEDIA_TYPE_PLAYLIST),
                folder(PLAYLISTS, context.getString(R.string.auto_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(DOWNLOADS, context.getString(R.string.auto_downloads), MediaMetadata.MEDIA_TYPE_PLAYLIST),
            )
            parentId == LIKED -> songs(LibraryManager.likedSongs.value)
            parentId == RECENT -> songs(LibraryManager.recentSongs.value)
            parentId == DOWNLOADS -> songs(
                Downloads.entries.value.values.filter { it.status == DownloadStatus.COMPLETED }
                    .sortedByDescending { it.updatedAt }.map { it.item }
            )
            parentId == PLAYLISTS -> LibraryManager.playlists.value.map { p ->
                folder(PLAYLIST_PREFIX + p.id, p.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, p.covers.firstOrNull())
            }
            parentId.startsWith(PLAYLIST_PREFIX) -> songs(
                LibraryManager.playlists.value.firstOrNull { it.id == parentId.removePrefix(PLAYLIST_PREFIX) }?.songs.orEmpty()
            )
            else -> emptyList()
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(paged(children, page, pageSize), params))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = known[mediaId] ?: LibraryManager.likedSongs.value.firstOrNull { it.id == mediaId }
        return Futures.immediateFuture(
            if (item != null) LibraryResult.ofItem(MediaItems.from(item), null)
            else LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
        )
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            val found = YouTubeClient.search(query, SearchFilter.SONGS).getOrNull()?.items.orEmpty().filter { it.isSong }
            searches[query] = found
            session.notifySearchResultChanged(browser, query, found.size, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.immediateFuture(LibraryResult.ofItemList(paged(songs(searches[query].orEmpty()), page, pageSize), params))

    /**
     * Items from other apps (Android Auto, Assistant, Bluetooth) arrive as bare ids. Fill in what the player needs;
     * our own controller already sends complete items, which pass through untouched.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        val needsSearch = mediaItems.firstOrNull { it.localConfiguration == null && it.mediaId.isBlank() }
            ?.requestMetadata?.searchQuery
        if (needsSearch != null) {
            val future = SettableFuture.create<List<MediaItem>>()
            scope.launch {
                val found = YouTubeClient.search(needsSearch, SearchFilter.SONGS).getOrNull()?.items.orEmpty().filter { it.isSong }
                future.set(found.take(25).map { MediaItems.from(it) })
            }
            return future
        }
        return Futures.immediateFuture(mediaItems.map(::complete))
    }

    private fun complete(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val id = item.mediaId
        known[id]?.let { return MediaItems.from(it) }
        return item.buildUpon().setUri(Uri.parse(SimpleItem.playbackUrl(id))).build()
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val saved = QueueStore.load(context)
        return if (saved == null || saved.items.isEmpty()) {
            val liked = LibraryManager.likedSongs.value.take(50)
            if (liked.isEmpty()) Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            else Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(liked.map { MediaItems.from(it) }, 0, 0L))
        } else {
            Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs))
        }
    }

    private fun songs(list: List<SimpleItem>): List<MediaItem> = list.filter { it.isSong }.map { song ->
        known[song.id] = song
        MediaItems.from(song)
    }

    private fun folder(id: String, title: String, type: Int, art: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setArtworkUri(art?.let(Uri::parse))
                .build()
        )
        .build()

    private fun paged(all: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(all)
        val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
        val to = (from + pageSize).coerceAtMost(all.size)
        return ImmutableList.copyOf(all.subList(from, to))
    }

    companion object {
        const val ROOT = "vyora:root"
        const val LIKED = "vyora:liked"
        const val RECENT = "vyora:recent"
        const val PLAYLISTS = "vyora:playlists"
        const val DOWNLOADS = "vyora:downloads"
        const val PLAYLIST_PREFIX = "vyora:pl:"
    }
}
