package com.vyora.music.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.PlaylistDetail
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.playback.PlayerManager
import com.vyora.music.ui.components.GlassMenu
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.components.LocalMenuBackdrop
import com.vyora.music.ui.components.MenuAction
import com.vyora.music.ui.design.Artwork
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.artworkKey
import com.vyora.music.ui.design.morphingCorner
import com.vyora.music.ui.design.rememberArtworkTint
import com.vyora.music.ui.design.sharedArtwork
import com.vyora.music.ui.glass.LocalAppBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.layerBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.vyora.music.ui.theme.LocalVyoraColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraText
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Contents of an album or playlist. [playlistId] is the YouTube `list=` value. */
@Composable
fun PlaylistScreen(
    playlistId: String,
    onBack: () -> Unit,
    /** queue, start index, shuffle on? */
    onPlay: (List<SimpleItem>, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** What the tapped row already knew, shown instantly while the page loads. */
    seedArt: String? = null,
    seedTitle: String? = null,
    seedOwner: String? = null,
) {
    var detail by remember(playlistId) { mutableStateOf<PlaylistDetail?>(null) }
    var songs by remember(playlistId) { mutableStateOf<List<SimpleItem>>(emptyList()) }
    var nextPage by remember(playlistId) { mutableStateOf<Page?>(null) }
    var loading by remember(playlistId) { mutableStateOf(true) }
    var loadingMore by remember(playlistId) { mutableStateOf(false) }
    var failed by remember(playlistId) { mutableStateOf(false) }
    var attempt by remember(playlistId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val bottomInset = LocalBottomBarInset.current

    LaunchedEffect(playlistId, attempt) {
        loading = true
        failed = false
        YouTubeClient.getPlaylist(playlistId).fold(
            onSuccess = {
                detail = it
                songs = it.page.items
                nextPage = it.page.nextPage
            },
            onFailure = { failed = true }
        )
        loading = false
    }

    fun loadMore() {
        val page = nextPage ?: return
        if (loading || loadingMore) return
        loadingMore = true
        scope.launch {
            YouTubeClient.getPlaylistMore(playlistId, page, detail?.thumbnail).fold(
                onSuccess = { more ->
                    val merged = (songs + more.items).distinctBy { it.id }
                    // No new rows: the cursor is stuck, stop paging.
                    nextPage = if (merged.size == songs.size) null else more.nextPage
                    songs = merged
                },
                onFailure = { /* keep cursor; scrolling again retries */ }
            )
            loadingMore = false
        }
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, songs.size) { if (nearEnd) loadMore() }

    // iOS 26.4: the whole page takes a deep version of the artwork colour and the cover
    // runs edge to edge at the top, fading into that colour.
    val tint by rememberArtworkTint(seedArt ?: detail?.thumbnail)
    // Dark: a deep shade of the artwork colour. Light: a soft pastel of it.
    val isDarkPage = LocalVyoraColors.current.isDark
    val pageTarget = if (isDarkPage) lerp(tint, Color.Black, 0.55f) else lerp(tint, Color.White, 0.8f)
    val onPage = VyoraText
    val page by animateColorAsState(pageTarget, tween(600), label = "pageTint")
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val queuedMsg = stringResource(R.string.toast_play_last)

    // Two glass sources, so nothing ever samples a layer that contains itself:
    //  - pageBackdrop: just the tinted page colour, sampled by in-list glass (cards, buttons);
    //  - listBackdrop: the whole scrolling page, sampled by the floating toolbar on top.
    val pageBackdrop = rememberLayerBackdrop()
    val listBackdrop = rememberLayerBackdrop()

    Box(modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().layerBackdrop(listBackdrop)) {
        Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop).background(page))
        CompositionLocalProvider(LocalAppBackdrop provides pageBackdrop) {
        // The header is there from the first frame (seeded from the tapped row), so the
        // cover has somewhere to fly to; loading / error states sit under it.
        LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp + bottomInset)
            ) {
                item(key = "header") {
                    Header(
                        playlistId = playlistId,
                        title = detail?.title ?: seedTitle,
                        owner = detail?.owner?.takeIf { it.isNotBlank() } ?: seedOwner,
                        art = detail?.thumbnail ?: seedArt,
                        artPlaceholderKey = seedArt,
                        totalSongs = detail?.totalSongs ?: -1L,
                        page = page,
                        loadedCount = songs.size,
                        canPlay = songs.isNotEmpty(),
                        onPlay = { onPlay(songs, 0, false) },
                        onShuffle = { onPlay(songs, songs.indices.random(), true) }
                    )
                }
                when {
                    loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(top = 36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = onPage, strokeWidth = 2.5.dp)
                        }
                    }
                    failed -> item(key = "failed") {
                        GlassEmptyState(
                            icon = Icons.Default.WifiOff,
                            title = stringResource(R.string.playlist_error_title),
                            body = stringResource(R.string.home_reco_error_body),
                            actionLabel = stringResource(R.string.try_again),
                            onAction = { attempt++ },
                            modifier = Modifier.padding(Spacing.ScreenH),
                        )
                    }
                    songs.isEmpty() -> item(key = "empty") {
                        GlassEmptyState(
                            icon = Icons.Default.MusicOff,
                            title = stringResource(R.string.playlist_empty),
                            body = stringResource(R.string.search_empty_body),
                        )
                    }
                }
                itemsIndexed(songs, key = { _, item -> item.id }) { index, item ->
                    ItemRow(
                        item,
                        index = index + 1,
                        onClick = { onPlay(songs, index, false) },
                        modifier = Modifier.padding(horizontal = Spacing.ScreenH),
                        showSeparator = index < songs.lastIndex,
                        secondaryColor = onPage.copy(alpha = 0.6f),
                    )
                }
                if (loadingMore) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = onPage, strokeWidth = 2.dp)
                        }
                    }
                }
            }

        }
      }

        // Floating iOS 26 toolbar: liquid-glass back / more buttons refracting the page.
        CompositionLocalProvider(LocalAppBackdrop provides listBackdrop, LocalMenuBackdrop provides listBackdrop) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.Rounded.ArrowBackIosNew,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
                size = 44.dp,
                iconSize = 20.dp,
                fill = onPage.copy(alpha = 0.10f),
            )
            Spacer(Modifier.weight(1f))
            // iOS 26 album toolbar: "+" adds the album to the Library, then turns into a check.
            val saved by LibraryManager.savedCollections.collectAsState()
            val isSaved = saved.any { it.id == playlistId }
            val title = detail?.title ?: seedTitle
            if (title != null) {
                val savedMsg = stringResource(R.string.library_added_toast)
                GlassIconButton(
                    icon = if (isSaved) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = stringResource(if (isSaved) R.string.library_remove else R.string.library_add),
                    onClick = {
                        if (!isSaved) Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
                        LibraryManager.toggleSaved(
                            SimpleItem(
                                id = playlistId,
                                title = title,
                                artist = detail?.owner?.takeIf { it.isNotBlank() } ?: seedOwner.orEmpty(),
                                thumbnail = detail?.thumbnail ?: seedArt,
                                url = YouTubeClient.playlistUrl(playlistId),
                                type = if (YouTubeClient.isAlbumId(playlistId)) ItemType.ALBUM else ItemType.PLAYLIST,
                                count = detail?.totalSongs ?: -1L,
                            )
                        )
                    },
                    size = 44.dp,
                    iconSize = 22.dp,
                    tint = if (isSaved) VyoraAccent else VyoraText,
                    fill = onPage.copy(alpha = 0.10f),
                )
                Spacer(Modifier.width(10.dp))
            }
            if (songs.isNotEmpty()) {
                Box {
                    GlassIconButton(
                        icon = Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.more),
                        onClick = { menu = true },
                        size = 44.dp,
                        iconSize = 22.dp,
                        fill = onPage.copy(alpha = 0.10f),
                    )
                    GlassMenu(
                        expanded = menu,
                        onDismiss = { menu = false },
                        actions = listOf(
                            MenuAction(stringResource(R.string.add_all_queue), Icons.AutoMirrored.Filled.QueueMusic) {
                                songs.forEach { PlayerManager.addToQueue(it) }
                                Toast.makeText(context, queuedMsg, Toast.LENGTH_SHORT).show()
                            },
                        ),
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun Header(
    playlistId: String,
    title: String?,
    owner: String?,
    art: String?,
    artPlaceholderKey: String?,
    totalSongs: Long,
    page: Color,
    loadedCount: Int,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    val onPage = VyoraText
    Box(Modifier.fillMaxWidth()) {
        // Lands from the list thumbnail: bounds fly, corners ease from 10dp to square.
        val corner by morphingCorner(from = 10.dp, to = 0.dp)
        Artwork(
            url = art,
            shape = if (corner == 0.dp) RectangleShape else RoundedCornerShape(corner),
            placeholder = Icons.Default.Album,
            placeholderSize = 64.dp,
            contentDescription = title,
            placeholderKey = artPlaceholderKey,
            modifier = Modifier.sharedArtwork(artworkKey(playlistId)).fillMaxWidth().aspectRatio(1f),
        )
        // Fade the cover into the page colour so the title sits on a calm field.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to page))
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = Spacing.ScreenH)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                color = onPage,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (!owner.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(owner, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal), color = onPage.copy(alpha = 0.75f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Total from YouTube if known, else what has been loaded so far.
            val count = totalSongs.takeIf { it > 0 }?.toInt() ?: loadedCount
            if (count > 0) {
                Spacer(Modifier.height(4.dp))
                Text(pluralStringResource(R.plurals.track_count, count, count), style = MaterialTheme.typography.bodySmall, color = onPage.copy(alpha = 0.6f))
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenH, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassButton(
            text = stringResource(R.string.play_all),
            icon = Icons.Default.PlayArrow,
            onClick = onPlay,
            enabled = canPlay,
            modifier = Modifier.weight(1f),
            content = onPage,
            fill = onPage.copy(alpha = 0.16f),
        )
        GlassButton(
            text = stringResource(R.string.shuffle),
            icon = Icons.Default.Shuffle,
            onClick = onShuffle,
            enabled = canPlay,
            modifier = Modifier.weight(1f),
            content = onPage,
            fill = onPage.copy(alpha = 0.16f),
        )
    }
}
