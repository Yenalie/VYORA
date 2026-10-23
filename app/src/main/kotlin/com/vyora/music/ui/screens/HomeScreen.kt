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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyora.music.R
import com.vyora.music.data.library.HomeContent
import com.vyora.music.data.library.HomeFeed
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.library.TopPick
import com.vyora.music.data.library.MixKind
import com.vyora.music.data.library.PersonalMix
import com.vyora.music.data.library.Station
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.AccountButton
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.components.SongMenu
import com.vyora.music.ui.design.Artwork
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.OnTabReselect
import com.vyora.music.ui.design.SectionTitle
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.liquidFill
import com.vyora.music.ui.design.rememberArtworkTint
import com.vyora.music.ui.design.scrollToTopSmooth
import com.vyora.music.ui.theme.GlassFillStrong
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraSurface
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted

object Collections {
    const val LIKED = "liked"
    const val RECENT = "recent"
    const val DOWNLOADS = "downloads"
}

@Composable
fun HomeScreen(
    onPlayList: (List<SimpleItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSearch: () -> Unit = {},
    onOpenCollection: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (SimpleItem) -> Unit = {},
    onStartRadio: (SimpleItem) -> Unit = {},
    onOpenRecap: () -> Unit = {},
    onOpenSpotify: (String) -> Unit = {},
) {
    val recent by LibraryManager.recentSongs.collectAsState()
    val liked by LibraryManager.likedSongs.collectAsState()
    val bottomInset = LocalBottomBarInset.current
    val chartQuery = stringResource(R.string.home_chart_query)
    var content by remember { mutableStateOf<HomeContent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var attempt by rememberSaveable { mutableStateOf(0) }
    val seeds = remember(recent, liked) { HomeFeed.seedArtists(recent, liked).joinToString(",") }

    LaunchedEffect(attempt, seeds, chartQuery) {
        if (attempt > 0) HomeFeed.invalidate()
        loading = content == null
        error = false
        HomeFeed.load(chartQuery, recent, liked).fold(
            onSuccess = { content = it },
            onFailure = { if (content == null) error = true },
        )
        loading = false
    }

    val topPicks = content?.topPicks.orEmpty()
    val topSongs = content?.topSongs.orEmpty()
    val artistShelves = content?.artistShelves.orEmpty()
    val mixes = content?.mixes.orEmpty()
    val madeForYou = content?.madeForYou.orEmpty()
    val stations = content?.stations.orEmpty()
    val albums = content?.albums.orEmpty()
    val musicVideos = content?.musicVideos.orEmpty()
    val replaySince = remember { replayYearStart() }
    val replayFlow = remember(replaySince) { LibraryManager.stats()?.topSongs(replaySince, 25) }
    val replayRows by (replayFlow ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    val replay = remember(replayRows) {
        replayRows.map { SimpleItem(id = it.itemId, title = it.title, artist = it.artist, thumbnail = it.thumbnail, url = SimpleItem.playbackUrl(it.itemId)) }
    }

    val spotifyProfile by com.vyora.music.data.spotify.SpotifyAccount.profile.collectAsState()
    var spotify by remember { mutableStateOf(SpotifyHomeCache.last) }
    LaunchedEffect(spotifyProfile?.id, attempt) {
        if (attempt > 0) SpotifyHomeCache.invalidate()
        spotify = SpotifyHomeCache.load()
    }
    val spotifyContext = androidx.compose.ui.platform.LocalContext.current
    val spotifyNoMatch = stringResource(R.string.spotify_no_match)
    val spotifyStarting = stringResource(R.string.spotify_starting)

    val listState = rememberLazyListState()
    OnTabReselect("home") { listState.scrollToTopSmooth() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(Spacing.Section),
    ) {
        item(key = "header") {
            LargeTitleHeader(
                title = stringResource(R.string.home),
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 14.dp),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassIconButton(Icons.Default.Search, stringResource(R.string.search), onOpenSearch, size = 44.dp, iconSize = 22.dp)
                        AccountButton(onClick = onOpenSettings)
                    }
                },
            )
        }

        item(key = "top_picks") {
            Column {
                SectionTitle(stringResource(R.string.top_picks), Modifier.padding(horizontal = Spacing.ScreenH))
                Spacer(Modifier.height(12.dp))
                when {
                    loading && topPicks.isEmpty() -> LoadingShelf()
                    error && topPicks.isEmpty() -> GlassEmptyState(
                        icon = Icons.Default.WifiOff,
                        title = stringResource(R.string.home_reco_error_title),
                        body = stringResource(R.string.home_reco_error_body),
                        actionLabel = stringResource(R.string.try_again),
                        onAction = { attempt++ },
                    )
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val queue = topPicks.map { it.item }
                        val keys0 = com.vyora.music.ui.design.uniqueKeys(topPicks) { "pick_" + it.item.id }
                        itemsIndexed(topPicks, key = { i, _ -> keys0[i] }) { i, pick ->
                            TopPickCard(pick) { onPlayList(queue, i) }
                        }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item(key = "recent") {
                Shelf(
                    title = stringResource(R.string.recently_played),
                    items = recent.take(15),
                    onTitle = { onOpenCollection(Collections.RECENT) },
                    onPlay = { i -> onPlayList(recent.take(15), i) },
                )
            }
        }

        spotify?.let { data ->
            spotifyHomeItems(
                data = data,
                onOpenList = onOpenSpotify,
                onOpenArtist = onOpenArtist,
                onPlayTracks = { tracks, index ->
                    android.widget.Toast.makeText(spotifyContext, spotifyStarting, android.widget.Toast.LENGTH_SHORT).show()
                    com.vyora.music.ui.screens.settings.SpotifyPlayer.play(tracks, index, false) {
                        android.widget.Toast.makeText(spotifyContext, spotifyNoMatch, android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        if (madeForYou.isNotEmpty()) {
            item(key = "made_for_you") {
                Column {
                    SectionTitle(stringResource(R.string.made_for_you), Modifier.padding(horizontal = Spacing.ScreenH))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val keys1 = com.vyora.music.ui.design.uniqueKeys(madeForYou) { "mfy_" + it.kind }
                        itemsIndexed(madeForYou, key = { i, _ -> keys1[i] }) { _, mix ->
                            MixCard(mix) { onPlayList(mix.songs, 0) }
                        }
                    }
                }
            }
        }

        if (stations.isNotEmpty()) {
            item(key = "stations") {
                Column {
                    SectionTitle(stringResource(R.string.home_stations), Modifier.padding(horizontal = Spacing.ScreenH))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val keys2 = com.vyora.music.ui.design.uniqueKeys(stations) { "station_" + it.seed.id }
                        itemsIndexed(stations, key = { i, _ -> keys2[i] }) { _, station ->
                            StationCard(station) { onStartRadio(station.seed) }
                        }
                    }
                }
            }
        }

        if (topSongs.isNotEmpty()) {
            item(key = "top_songs") {
                Column {
                    SectionTitle(stringResource(R.string.top_songs), Modifier.padding(horizontal = Spacing.ScreenH))
                    Spacer(Modifier.height(8.dp))
                    val columns = topSongs.chunked(4)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(columns) { c, column ->
                            Column(Modifier.width(310.dp)) {
                                column.forEachIndexed { r, item ->
                                    ItemRow(
                                        item = item,
                                        onClick = { onPlayList(topSongs, c * 4 + r) },
                                        showSeparator = r < column.lastIndex,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val mixKeys = com.vyora.music.ui.design.uniqueKeys(mixes) { "mix_" + it.seed.id }
        mixes.forEachIndexed { mixIndex, mix ->
            item(key = mixKeys[mixIndex]) {
                Shelf(
                    title = stringResource(R.string.home_because_you_listened, mix.seed.title),
                    items = mix.songs,
                    onTitle = { onPlayList(listOf(mix.seed) + mix.songs, 0) },
                    onPlay = { i -> onPlayList(mix.songs, i) },
                )
            }
        }

        if (albums.size >= 3) {
            item(key = "albums") {
                Column {
                    SectionTitle(stringResource(R.string.home_albums), Modifier.padding(horizontal = Spacing.ScreenH))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val keys3 = com.vyora.music.ui.design.uniqueKeys(albums) { "album_" + it.id }
                        itemsIndexed(albums, key = { i, _ -> keys3[i] }) { _, album ->
                            SquareTile(album) { onOpenAlbum(album) }
                        }
                    }
                }
            }
        }

        val shelfKeys = com.vyora.music.ui.design.uniqueKeys(artistShelves) { "more_" + it.artist }
        artistShelves.forEachIndexed { shelfIndex, shelf ->
            item(key = shelfKeys[shelfIndex]) {
                Shelf(
                    title = stringResource(R.string.home_more_from, shelf.artist),
                    items = shelf.songs,
                    onTitle = { onOpenArtist(shelf.artist) },
                    onPlay = { i -> onPlayList(shelf.songs, i) },
                )
            }
        }

        if (musicVideos.size >= 3) {
            item(key = "videos") {
                Column {
                    SectionTitle(stringResource(R.string.home_music_videos), Modifier.padding(horizontal = Spacing.ScreenH))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val keys4 = com.vyora.music.ui.design.uniqueKeys(musicVideos) { "video_" + it.id }
                        itemsIndexed(musicVideos, key = { i, _ -> keys4[i] }) { i, video ->
                            VideoTile(video) { onPlayList(musicVideos, i) }
                        }
                    }
                }
            }
        }

        if (replay.size >= 5) {
            item(key = "replay") {
                Shelf(
                    title = stringResource(R.string.home_replay),
                    items = replay,
                    onTitle = onOpenRecap,
                    onPlay = { i -> onPlayList(replay, i) },
                )
            }
        }

        if (liked.isNotEmpty()) {
            item(key = "liked") {
                Shelf(
                    title = stringResource(R.string.favorite_songs),
                    items = liked.take(15),
                    onTitle = { onOpenCollection(Collections.LIKED) },
                    onPlay = { i -> onPlayList(liked.take(15), i) },
                )
            }
        }

        if (recent.isEmpty() && liked.isEmpty() && !loading && error && content == null) {
            item(key = "empty") {
                GlassEmptyState(
                    icon = Icons.Default.Headphones,
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    actionLabel = stringResource(R.string.home_empty_action),
                    onAction = onOpenSearch,
                )
            }
        }
    }
}

@Composable
private fun TopPickCard(pick: TopPick, onClick: () -> Unit) {
    val item = pick.item
    val tint by rememberArtworkTint(item.thumbnail)
    val panel by animateColorAsState(lerp(tint, Color.Black, 0.35f), tween(500), label = "pickPanel")
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(236.dp)) {
        Text(
            pick.becauseOf?.let { stringResource(R.string.home_because_you_listened, it) } ?: stringResource(R.string.made_for_you),
            color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Box {
            Column(
                Modifier
                    .clip(GlassShapes.Large)
                    .glassClickable(pressedScale = 0.97f, onLongClick = { menu = true }, onClick = onClick)
                    .background(VyoraSurface)
            ) {
                Box {
                    Artwork(
                        url = item.thumbnail,
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        placeholderSize = 40.dp,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, panel)))
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(34.dp)
                            .liquidFill(shape = GlassShapes.Pill, fill = GlassFillStrong),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.play), tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(panel)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .height(52.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.artist.isNotBlank()) {
                        Text(item.artist, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            SongMenu(item = item, expanded = menu, onDismiss = { menu = false })
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    items: List<SimpleItem>,
    onTitle: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    Column {
        SectionTitle(title, Modifier.padding(horizontal = Spacing.ScreenH), onAction = onTitle)
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val keys5 = com.vyora.music.ui.design.uniqueKeys(items) { title + it.id }
            itemsIndexed(items, key = { i, _ -> keys5[i] }) { i, item ->
                SquareTile(item) { onPlay(i) }
            }
        }
    }
}

@Composable
fun SquareTile(item: SimpleItem, modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 160.dp, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier.width(width)) {
        Column(Modifier.glassClickable(pressedScale = 0.96f, onLongClick = { menu = true }, onClick = onClick)) {
            Artwork(
                url = item.thumbnail,
                shape = GlassShapes.Small,
                placeholderSize = 32.dp,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Spacer(Modifier.height(7.dp))
            Text(item.title, color = VyoraText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.artist.isNotBlank()) {
                Text(item.artist, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SongMenu(item = item, expanded = menu, onDismiss = { menu = false })
    }
}

@Composable
private fun LoadingShelf() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenH)
            .height(120.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = VyoraAccent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.home_loading), color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun replayYearStart(): Long = java.util.Calendar.getInstance().run {
    set(get(java.util.Calendar.YEAR), java.util.Calendar.JANUARY, 1, 0, 0, 0)
    set(java.util.Calendar.MILLISECOND, 0)
    timeInMillis
}

/** Gradient sleeves in the spirit of Apple Music's personal mixes, one colour story per mix. */
private fun mixColors(kind: MixKind): List<Color> = when (kind) {
    MixKind.FAVORITES -> listOf(Color(0xFFFF5E7E), Color(0xFFE0245E), Color(0xFF8E1446))
    MixKind.DISCOVERY -> listOf(Color(0xFF5AD8E6), Color(0xFF2F8FD8), Color(0xFF1C3E8C))
    MixKind.REDISCOVER -> listOf(Color(0xFFC79BFF), Color(0xFF8A5CF5), Color(0xFF4B2AA8))
}

@Composable
private fun mixTitle(kind: MixKind): String = stringResource(
    when (kind) {
        MixKind.FAVORITES -> R.string.mix_favorites
        MixKind.DISCOVERY -> R.string.mix_discovery
        MixKind.REDISCOVER -> R.string.mix_rediscover
    }
)

@Composable
private fun MixCard(mix: PersonalMix, onClick: () -> Unit) {
    val title = mixTitle(mix.kind)
    val colors = mixColors(mix.kind)
    val artists = remember(mix) {
        mix.songs.map { com.vyora.music.data.library.primaryArtist(it.artist) }.filter { it.isNotBlank() }.distinct().take(3).joinToString(", ")
    }
    Column(Modifier.width(160.dp).glassClickable(pressedScale = 0.96f, onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(GlassShapes.Small)
                .background(Brush.linearGradient(colors)),
        ) {
            // Three covers fanned at the top, the mix name set large at the bottom, as Apple lays out its mixes.
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy((-14).dp),
            ) {
                mix.songs.distinctBy { it.thumbnail }.take(3).forEach { song ->
                    Artwork(
                        url = song.thumbnail,
                        shape = GlassShapes.Small,
                        placeholderSize = 14.dp,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, lineHeight = 24.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
        Spacer(Modifier.height(7.dp))
        // The sleeve already names the mix; underneath, Apple lists who is in it.
        Text(artists, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StationCard(station: Station, onClick: () -> Unit) {
    val title = station.artist?.let { stringResource(R.string.station_artist, it) } ?: stringResource(R.string.station_discovery)
    val tint by rememberArtworkTint(station.seed.thumbnail)
    Column(Modifier.width(160.dp).glassClickable(pressedScale = 0.96f, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(GlassShapes.Small)) {
            Artwork(
                url = station.seed.thumbnail,
                shape = androidx.compose.ui.graphics.RectangleShape,
                placeholderSize = 32.dp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to lerp(tint, Color.Black, 0.45f)))
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Radio, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(R.string.station_label),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(title, color = VyoraText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(station.seed.artist, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VideoTile(item: SimpleItem, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.width(260.dp)) {
        Column(Modifier.glassClickable(pressedScale = 0.97f, onLongClick = { menu = true }, onClick = onClick)) {
            Artwork(
                url = item.thumbnail,
                shape = GlassShapes.Small,
                placeholderSize = 32.dp,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Spacer(Modifier.height(7.dp))
            Text(item.title, color = VyoraText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.artist.isNotBlank()) {
                Text(item.artist, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SongMenu(item = item, expanded = menu, onDismiss = { menu = false })
    }
}
