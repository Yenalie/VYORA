package com.vyora.music.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.spotify.SpotifyAccount
import com.vyora.music.data.spotify.SpotifyCard
import com.vyora.music.data.spotify.SpotifyPlaylistInfo
import com.vyora.music.data.spotify.SpotifyShelf
import com.vyora.music.data.spotify.SpotifyTrackInfo
import com.vyora.music.ui.design.Artwork
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.SectionTitle
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.theme.LocalVyoraColors
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** What the Spotify part of Home shows: the user's quick-access grid (signed in only) and Spotify's own shelves. */
@Immutable
data class SpotifyHomeData(
    val signedIn: Boolean,
    val quick: List<SpotifyPlaylistInfo>,
    val shelves: List<SpotifyShelf>,
)

/** Keeps the last Spotify Home for a while so switching tabs doesn't refetch it. */
object SpotifyHomeCache {
    private const val TTL_MS = 15 * 60_000L
    @Volatile var last: SpotifyHomeData? = null
        private set
    @Volatile private var loadedAt = 0L

    fun invalidate() { loadedAt = 0L }

    /** Never throws: a Spotify failure just leaves this part of Home out. */
    suspend fun load(): SpotifyHomeData? {
        val signedIn = SpotifyAccount.isSignedIn
        last?.takeIf { it.signedIn == signedIn && System.currentTimeMillis() - loadedAt < TTL_MS }?.let { return it }
        val fresh = coroutineScope {
            val home = async { runCatching { SpotifyAccount.home() }.getOrNull() }
            val mine = async { if (signedIn) runCatching { SpotifyAccount.playlists(0, 7).items }.getOrNull().orEmpty() else emptyList() }
            val quick = if (signedIn) {
                // Spotify's "Good evening" grid: Liked Songs first, then the newest playlists.
                listOf(SpotifyPlaylistInfo(SpotifyAccount.LIKED_URI, "", null, null, null)) + mine.await().take(7)
            } else emptyList()
            val shelves = home.await()?.shelves.orEmpty()
            if (shelves.isEmpty() && quick.isEmpty()) null else SpotifyHomeData(signedIn, quick, shelves.take(if (signedIn) 8 else 5))
        }
        if (fresh != null) {
            last = fresh
            loadedAt = System.currentTimeMillis()
        }
        return fresh ?: last
    }
}

/** Spotify's Home, laid out like Spotify: a two-column quick grid, then horizontal shelves. */
fun LazyListScope.spotifyHomeItems(
    data: SpotifyHomeData,
    onOpenList: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTracks: (List<SpotifyTrackInfo>, Int) -> Unit,
) {
    item(key = "spotify_header") {
        SpotifyHeader(Modifier.padding(horizontal = Spacing.ScreenH))
    }
    if (data.quick.isNotEmpty()) {
        item(key = "spotify_quick") {
            QuickGrid(data.quick, onOpenList)
        }
    }
    data.shelves.forEachIndexed { index, shelf ->
        item(key = "spotify_shelf_${index}_${shelf.title}") {
            SpotifyShelfRow(shelf, onOpenList, onOpenArtist, onPlayTracks)
        }
    }
}

private val SpotifyGreen = Color(0xFF1ED760)

@Composable
private fun SpotifyHeader(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(SpotifyGreen),
            contentAlignment = Alignment.Center,
        ) {
            // Three stacked arcs, drawn as rounded bars: a neutral nod to the Spotify mark without its logo file.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listOf(12.dp, 10.dp, 8.dp).forEach { w ->
                    Box(Modifier.width(w).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.Black))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.spotify_home_title),
            color = VyoraText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun QuickGrid(items: List<SpotifyPlaylistInfo>, onOpen: (String) -> Unit) {
    val liked = stringResource(R.string.spotify_liked)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenH),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    QuickTile(
                        playlist = p,
                        title = if (p.uri == SpotifyAccount.LIKED_URI) liked else p.name,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(p.uri) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickTile(playlist: SpotifyPlaylistInfo, title: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalVyoraColors.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(colors.surfaceElevated)
            .glassClickable(pressedScale = 0.97f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playlist.uri == SpotifyAccount.LIKED_URI) {
            LikedArt(Modifier.size(56.dp))
        } else {
            Artwork(
                url = playlist.imageUrl,
                shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                placeholderSize = 20.dp,
                contentDescription = title,
                modifier = Modifier.size(56.dp),
            )
        }
        Text(
            title,
            color = VyoraText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

/** Spotify's Liked Songs tile: violet-to-mint gradient with a white heart. */
@Composable
fun LikedArt(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)) {
    Box(
        modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4EFD9)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f))
    }
}

@Composable
private fun SpotifyShelfRow(
    shelf: SpotifyShelf,
    onOpenList: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTracks: (List<SpotifyTrackInfo>, Int) -> Unit,
) {
    val tracks = shelf.cards.mapNotNull { it.track }
    Column {
        SectionTitle(shelf.title, Modifier.padding(horizontal = Spacing.ScreenH))
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.ScreenH),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val keys0 = com.vyora.music.ui.design.uniqueKeys(shelf.cards) { c -> shelf.title + c.uri }
            itemsIndexed(shelf.cards, key = { i, _ -> keys0[i] }) { _, card ->
                SpotifyTile(card, width = 150.dp) {
                    when (card.kind) {
                        SpotifyCard.Kind.TRACK -> onPlayTracks(tracks, tracks.indexOf(card.track).coerceAtLeast(0))
                        SpotifyCard.Kind.ARTIST -> onOpenArtist(card.title)
                        else -> onOpenList(card.uri)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyTile(card: SpotifyCard, width: Dp, onClick: () -> Unit) {
    val artist = card.kind == SpotifyCard.Kind.ARTIST
    Column(
        Modifier.width(width).glassClickable(pressedScale = 0.96f, onClick = onClick),
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(
            url = card.imageUrl,
            shape = if (artist) CircleShape else GlassShapes.Small,
            placeholder = if (artist) Icons.Default.Person else Icons.Default.MusicNote,
            placeholderSize = 32.dp,
            contentDescription = card.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            card.title,
            color = VyoraText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (artist) TextAlign.Center else TextAlign.Start,
        )
        val sub = if (artist) stringResource(R.string.spotify_artist) else card.subtitle
        if (sub.isNotBlank()) {
            Text(
                sub,
                color = VyoraTextMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (artist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}
