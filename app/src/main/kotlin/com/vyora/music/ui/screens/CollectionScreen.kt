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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.theme.VyoraTextMuted

/** Full song list for a Library category, Apple Music style: back, large title, Play/Shuffle. */
@Composable
fun CollectionScreen(
    kind: String,
    onBack: () -> Unit,
    onPlay: (List<SimpleItem>, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liked by LibraryManager.likedSongs.collectAsState()
    val recent by LibraryManager.recentSongs.collectAsState()
    val songs = if (kind == Collections.RECENT) recent else liked
    val title = stringResource(if (kind == Collections.RECENT) R.string.recently_played else R.string.favorite_songs)
    val bottomInset = LocalBottomBarInset.current

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 28.dp + bottomInset),
    ) {
        item(key = "top") {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack, size = 44.dp, iconSize = 20.dp)
                Spacer(Modifier.height(14.dp))
                LargeTitleHeader(title = title)
                if (songs.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.track_count, songs.size, songs.size),
                        color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GlassButton(stringResource(R.string.play_all), { onPlay(songs, 0, false) }, Modifier.weight(1f), icon = Icons.Default.PlayArrow)
                        GlassButton(stringResource(R.string.shuffle), { onPlay(songs, songs.indices.random(), true) }, Modifier.weight(1f), icon = Icons.Default.Shuffle)
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
        if (songs.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp)) {
                    GlassEmptyState(
                        icon = Icons.Default.MusicNote,
                        title = stringResource(R.string.collection_empty_title),
                        body = stringResource(R.string.collection_empty_body),
                    )
                }
            }
        } else {
            itemsIndexed(songs, key = { _, it -> it.id }) { i, item ->
                ItemRow(item = item, onClick = { onPlay(songs, i, false) }, showSeparator = i < songs.lastIndex)
            }
        }
    }
}
