package com.vyora.music.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.library.primaryArtist
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SearchFilter
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.components.SongMenu
import com.vyora.music.ui.design.Artwork
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.SectionTitle
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.rememberArtworkTint
import com.vyora.music.ui.theme.LocalVyoraColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraOnAccent
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class ArtistPage(
    val image: String?,
    val topSongs: List<SimpleItem>,
    val albums: List<SimpleItem>,
    val videos: List<SimpleItem>,
)

internal suspend fun loadArtistPage(name: String, artistId: String?, seedArt: String?): ArtistPage = coroutineScope {
    val songs = async { YouTubeClient.search(name, SearchFilter.SONGS) }
    val albums = async { YouTubeClient.search(name, SearchFilter.ALBUMS) }
    val videos = async { YouTubeClient.search(name, SearchFilter.VIDEOS) }
    val artists = async { YouTubeClient.search(name, SearchFilter.ARTISTS) }
    val sameName = artists.await().getOrNull()?.items.orEmpty()
        .filter { it.type == ItemType.ARTIST && it.title.equals(name, ignoreCase = true) }
    val channel = sameName.firstOrNull { it.id == artistId } ?: sameName.maxByOrNull { it.count }
    val channelId = artistId ?: channel?.id
    val mine: (SimpleItem) -> Boolean = { item ->
        if (channelId != null && item.artistId != null) item.artistId == channelId
        else creditsArtist(item.artist, name)
    }
    val songPage = songs.await().getOrNull()
    var songList = songPage?.items.orEmpty().filter { it.type == ItemType.SONG && mine(it) }
    if (songList.size < 10) {
        val more = songPage?.nextPage?.let { YouTubeClient.search(name, SearchFilter.SONGS, it).getOrNull() }
        songList = songList + more?.items.orEmpty().filter { it.type == ItemType.SONG && mine(it) }
    }
    if (songList.size < 10 && songList.isNotEmpty()) {
        val radio = YouTubeClient.radio(songList.first().id).getOrNull().orEmpty()
            .filter { (channelId != null && it.artistId == channelId) || (it.artistId == null && creditsArtist(it.artist, name)) }
        songList = songList + radio
    }
    val albumList = albums.await().getOrNull()?.items.orEmpty().filter(mine)
    val videoList = videos.await().getOrNull()?.items.orEmpty().filter(mine)
    val image = channel?.thumbnail ?: seedArt
    ArtistPage(
        image = image ?: songList.firstOrNull()?.thumbnail,
        topSongs = songList.distinctBy { it.id },
        albums = albumList.distinctBy { it.id },
        videos = videoList.distinctBy { it.id },
    )
}

internal fun creditsArtist(artistField: String, name: String): Boolean {
    val n = name.trim().lowercase()
    if (n.isBlank()) return false
    return artistField.lowercase()
        .split(",", "&", "•", " feat.", " ft.", " x ", " dan ", " and ")
        .any { it.trim() == n }
}

@Composable
fun ArtistScreen(
    name: String,
    seedArt: String?,
    artistId: String? = null,
    onBack: () -> Unit,
    onPlay: (List<SimpleItem>, Int, Boolean) -> Unit,
    onOpenCollection: (SimpleItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember(name) { mutableStateOf<ArtistPage?>(null) }
    var failed by remember(name) { mutableStateOf(false) }
    var attempt by remember(name) { mutableStateOf(0) }
    var showAllSongs by remember(name) { mutableStateOf(false) }
    val bottomInset = LocalBottomBarInset.current

    LaunchedEffect(name, artistId, attempt) {
        failed = false
        val loaded = loadArtistPage(name, artistId, seedArt)
        if (loaded.topSongs.isEmpty() && loaded.albums.isEmpty() && loaded.videos.isEmpty()) failed = true else page = loaded
    }

    val tint by rememberArtworkTint(page?.image ?: seedArt)
    val dark = LocalVyoraColors.current.isDark
    val bg by animateColorAsState(if (dark) lerp(tint, Color.Black, 0.7f) else lerp(tint, Color.White, 0.85f), tween(600), label = "artistBg")
    val songs = page?.topSongs.orEmpty()

    com.vyora.music.ui.design.GlassPage(
        background = bg,
        modifier = modifier,
        overlay = {
            GlassIconButton(
                icon = Icons.Rounded.ArrowBackIosNew,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
                size = 44.dp,
                iconSize = 20.dp,
                modifier = Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
            )
        },
    ) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp + bottomInset),
        ) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().aspectRatio(1.05f)) {
                    Artwork(
                        url = (page?.image ?: seedArt)?.let { YouTubeClient.artUrl(it, 1080) },
                        shape = RectangleShape,
                        placeholder = Icons.Default.Person,
                        placeholderSize = 72.dp,
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to bg)))
                    Row(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Spacing.ScreenH, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name, color = VyoraText, style = MaterialTheme.typography.headlineLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (songs.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .glassClickable(pressedScale = 0.9f) { onPlay(songs, 0, false) }
                                    .background(VyoraAccent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.PlayArrow, stringResource(R.string.play), tint = VyoraOnAccent, modifier = Modifier.size(30.dp))
                            }
                        }
                    }
                }
            }

            when {
                failed -> item(key = "failed") {
                    GlassEmptyState(
                        icon = Icons.Default.WifiOff,
                        title = stringResource(R.string.artist_error_title),
                        body = stringResource(R.string.home_reco_error_body),
                        actionLabel = stringResource(R.string.try_again),
                        onAction = { attempt++ },
                    )
                }
                page == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = VyoraAccent, strokeWidth = 2.5.dp)
                    }
                }
            }

            if (songs.isNotEmpty()) {
                item(key = "songs_title") {
                    Column(Modifier.padding(horizontal = Spacing.ScreenH)) {
                        Spacer(Modifier.height(8.dp))
                        SectionTitle(
                            stringResource(R.string.top_songs),
                            onAction = if (songs.size > 5) ({ showAllSongs = !showAllSongs }) else null,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                val shown = if (showAllSongs) songs else songs.take(5)
                itemsIndexed(shown, key = { _, it -> "song_" + it.id }) { i, item ->
                    ItemRow(
                        item = item,
                        onClick = { onPlay(songs, i, false) },
                        modifier = Modifier.padding(horizontal = Spacing.ScreenH),
                        showSeparator = i < shown.lastIndex,
                    )
                }
                item(key = "shuffle") {
                    Row(Modifier.padding(horizontal = Spacing.ScreenH, vertical = 12.dp)) {
                        com.vyora.music.ui.design.GlassButton(
                            stringResource(R.string.shuffle),
                            { onPlay(songs, songs.indices.random(), true) },
                            Modifier.fillMaxWidth(),
                            icon = Icons.Default.Shuffle,
                        )
                    }
                }
            }

            page?.albums?.takeIf { it.isNotEmpty() }?.let { albums ->
                item(key = "albums") {
                    CollectionShelf(stringResource(R.string.tab_albums), albums, onOpenCollection)
                }
            }
            page?.videos?.takeIf { it.isNotEmpty() }?.let { videos ->
                item(key = "videos") {
                    Column(Modifier.padding(top = Spacing.Section)) {
                        SectionTitle(stringResource(R.string.artist_music_videos), Modifier.padding(horizontal = Spacing.ScreenH))
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(videos, key = { _, it -> "v_" + it.id }) { i, item ->
                                VideoTile(item) { onPlay(videos, i, false) }
                            }
                        }
                    }
                }
            }
        }

    }
}

@Composable
fun CollectionShelf(title: String, items: List<SimpleItem>, onOpen: (SimpleItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = Spacing.Section)) {
        SectionTitle(title, Modifier.padding(horizontal = Spacing.ScreenH))
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(items, key = { _, it -> "c_" + it.id }) { _, item ->
                CollectionTile(item, width = 160.dp) { onOpen(item) }
            }
        }
    }
}

@Composable
fun CollectionTile(item: SimpleItem, modifier: Modifier = Modifier, width: Dp = 160.dp, onClick: () -> Unit) {
    Column(modifier.then(if (width != Dp.Unspecified) Modifier.width(width) else Modifier).glassClickable(pressedScale = 0.96f, onClick = onClick)) {
        Artwork(
            url = item.thumbnail,
            shape = GlassShapes.Small,
            placeholder = Icons.Default.Album,
            placeholderSize = 32.dp,
            contentDescription = item.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(7.dp))
        Text(item.title, color = VyoraText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val owner = primaryArtist(item.artist)
        if (owner.isNotBlank()) {
            Text(owner, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VideoTile(item: SimpleItem, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.width(260.dp)) {
        Column(Modifier.glassClickable(pressedScale = 0.96f, onLongClick = { menu = true }, onClick = onClick)) {
            Artwork(
                url = item.thumbnail,
                shape = GlassShapes.Small,
                placeholderSize = 32.dp,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Spacer(Modifier.height(7.dp))
            Text(item.title, color = VyoraText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SongMenu(item = item, expanded = menu, onDismiss = { menu = false })
    }
}
