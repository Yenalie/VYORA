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
import com.vyora.music.R
import com.vyora.music.data.library.HomeContent
import com.vyora.music.data.library.HomeFeed
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.library.TopPick
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

/** Collection routes opened from Home / Library shelves. */
object Collections {
    const val LIKED = "liked"
    const val RECENT = "recent"
}

/**
 * Apple Music "Home" (iOS 26): large title, "Top Picks" tall cards whose lower panel takes
 * the artwork colour, shelves with "Title ›" headers that open the full list, and a paged
 * "Top Songs" shelf of 4-row columns.
 */
@Composable
fun HomeScreen(
    onPlayList: (List<SimpleItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSearch: () -> Unit = {},
    onOpenCollection: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
) {
    val recent by LibraryManager.recentSongs.collectAsState()
    val liked by LibraryManager.likedSongs.collectAsState()
    val bottomInset = LocalBottomBarInset.current
    val chartQuery = stringResource(R.string.home_chart_query)
    var content by remember { mutableStateOf<HomeContent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var attempt by rememberSaveable { mutableStateOf(0) }
    // Seeds only change when the user's top artists change, not on every play.
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

    // Tapping the tab again scrolls back to the top.
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
                        // Apple Music puts the account / settings entry in a round avatar button.
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
                        itemsIndexed(topPicks, key = { _, it -> "pick_" + it.item.id }) { i, pick ->
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

        artistShelves.forEach { shelf ->
            item(key = "more_" + shelf.artist) {
                Shelf(
                    title = stringResource(R.string.home_more_from, shelf.artist),
                    items = shelf.songs,
                    onTitle = { onOpenArtist(shelf.artist) },
                    onPlay = { i -> onPlayList(shelf.songs, i) },
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

/**
 * Apple Music "Top Picks" card: a caption above, square artwork, and a lower panel filled
 * with a darkened version of the artwork colour carrying the title.
 */
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
                    // Soft blend from the artwork into the colour panel, as on iOS.
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

/** A "Title ›" shelf of square tiles. */
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
            itemsIndexed(items, key = { _, it -> title + it.id }) { i, item ->
                SquareTile(item) { onPlay(i) }
            }
        }
    }
}

/** Square artwork with a one-line title and secondary artist line. Long press = menu. */
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
