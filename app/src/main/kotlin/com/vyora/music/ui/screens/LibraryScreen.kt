package com.vyora.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.AccountButton
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.Hairline
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.OnTabReselect
import com.vyora.music.ui.design.SectionTitle
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.scrollToTopSmooth
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted

/**
 * Apple Music "Library": large title, a list of categories (accent glyph, label, count,
 * chevron, hairline) and a two-column "Recently Added" grid below.
 */
@Composable
fun LibraryScreen(
    onPlay: (List<SimpleItem>, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onPlayShuffled: (List<SimpleItem>) -> Unit = { list -> if (list.isNotEmpty()) onPlay(list.shuffled(), 0) },
    onOpenCollection: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenPage: (String) -> Unit = {},
    onOpenSaved: (SimpleItem) -> Unit = {},
) {
    val liked by LibraryManager.likedSongs.collectAsState()
    val recent by LibraryManager.recentSongs.collectAsState()
    val bottomInset = LocalBottomBarInset.current
    val playlists by LibraryManager.playlists.collectAsState()
    val saved by LibraryManager.savedCollections.collectAsState()
    val artistCount = remember(liked, playlists, saved) { libraryArtists(liked, playlists, saved).size }
    // Newest first from both lists, interleaved so saved albums and favorites both show.
    val added = remember(liked, saved) {
        val out = mutableListOf<SimpleItem>()
        val a = saved.iterator(); val b = liked.iterator()
        while ((a.hasNext() || b.hasNext()) && out.size < 20) {
            if (a.hasNext()) out += a.next()
            if (b.hasNext() && out.size < 20) out += b.next()
        }
        out
    }
    val songsInAdded = added.filter { it.type == ItemType.SONG }

    // Tapping the tab again scrolls back to the top.
    val listState = rememberLazyListState()
    OnTabReselect("library") { listState.scrollToTopSmooth() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 28.dp + bottomInset),
    ) {
        item(key = "header") {
            LargeTitleHeader(
                title = stringResource(R.string.library_title),
                modifier = Modifier.statusBarsPadding().padding(top = 14.dp, bottom = 10.dp),
                trailing = {
                    AccountButton(onClick = onOpenSettings)
                },
            )
        }
        item(key = "categories") {
            // iOS 26 Apple Music: a plain list (no card), red glyphs, inset hairlines.
            Column(Modifier.fillMaxWidth()) {
                // Apple Music order: Playlists, Artists, Albums, Songs, then history.
                CategoryRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.library_playlists), playlists.size) {
                    onOpenPage(LibraryPages.PLAYLISTS)
                }
                CategoryRow(Icons.Default.Mic, stringResource(R.string.library_artists), artistCount) {
                    onOpenPage(LibraryPages.ARTISTS)
                }
                CategoryRow(Icons.Default.Album, stringResource(R.string.library_albums), saved.size) {
                    onOpenPage(LibraryPages.ALBUMS)
                }
                CategoryRow(Icons.Default.Star, stringResource(R.string.favorite_songs), liked.size) {
                    onOpenCollection(Collections.LIKED)
                }
                CategoryRow(Icons.Default.History, stringResource(R.string.recently_played), recent.size, last = true) {
                    onOpenCollection(Collections.RECENT)
                }
            }
        }

        item(key = "added_title") {
            Spacer(Modifier.height(Spacing.Section))
            SectionTitle(stringResource(R.string.recently_added))
            Spacer(Modifier.height(12.dp))
        }
        if (added.isEmpty()) {
            item(key = "empty") {
                GlassEmptyState(
                    icon = Icons.Default.LibraryMusic,
                    title = stringResource(R.string.library_liked_empty_title),
                    body = stringResource(R.string.library_liked_empty_body),
                )
            }
        } else {
            val rows = added.chunked(2)
            items(rows.size, key = { "added_$it" }) { r ->
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    rows[r].forEachIndexed { c, item ->
                        Box(Modifier.weight(1f)) {
                            if (item.isSong) {
                                SquareTile(item, Modifier.fillMaxWidth(), width = androidx.compose.ui.unit.Dp.Unspecified) {
                                    onPlay(songsInAdded, songsInAdded.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
                                }
                            } else {
                                CollectionTile(item, Modifier.fillMaxWidth(), width = androidx.compose.ui.unit.Dp.Unspecified) { onOpenSaved(item) }
                            }
                        }
                    }
                    if (rows[r].size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(icon: ImageVector, label: String, count: Int, last: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .glassClickable(pressedScale = 0.985f, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = VyoraAccent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(label, color = VyoraText, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), modifier = Modifier.weight(1f))
            if (count > 0) Text(count.toString(), color = VyoraTextMuted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = VyoraTextFaint, modifier = Modifier.size(24.dp))
        }
        if (!last) Hairline(Modifier.align(Alignment.BottomStart), startIndent = 40.dp)
    }
}
