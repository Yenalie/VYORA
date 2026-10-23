package com.vyora.music.ui.screens

import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vyora.music.R
import com.vyora.music.data.download.DownloadEntry
import com.vyora.music.data.download.DownloadStatus
import com.vyora.music.data.download.Downloads
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted

@Composable
fun CollectionScreen(
    kind: String,
    onBack: () -> Unit,
    onPlay: (List<SimpleItem>, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val liked by LibraryManager.likedSongs.collectAsState()
    val recent by LibraryManager.recentSongs.collectAsState()
    val downloads by Downloads.entries.collectAsState()
    val isDownloads = kind == Collections.DOWNLOADS
    val finished = remember(downloads) {
        downloads.values.filter { it.status == DownloadStatus.COMPLETED }.sortedByDescending { it.updatedAt }
    }
    val pending = remember(downloads) {
        downloads.values.filter { it.status != DownloadStatus.COMPLETED }.sortedBy { it.updatedAt }
    }
    val songs = when (kind) {
        Collections.RECENT -> recent
        Collections.DOWNLOADS -> finished.map { it.item }
        else -> liked
    }
    val title = stringResource(
        when (kind) {
            Collections.RECENT -> R.string.recently_played
            Collections.DOWNLOADS -> R.string.downloads
            else -> R.string.favorite_songs
        }
    )
    val bottomInset = LocalBottomBarInset.current
    val canDownloadAll = kind == Collections.LIKED && liked.any { downloads[it.id] == null }
    val downloadStarted = stringResource(R.string.toast_download_all_started)
    val usedBytes = remember(finished.size) { if (isDownloads) Downloads.usedBytes(context) else 0L }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 28.dp + bottomInset),
    ) {
        item(key = "top") {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack, size = 44.dp, iconSize = 20.dp)
                    Spacer(Modifier.weight(1f))
                    if (canDownloadAll) {
                        GlassIconButton(
                            Icons.Default.FileDownload,
                            stringResource(R.string.download_all),
                            {
                                Downloads.downloadAll(context, liked)
                                Toast.makeText(context, downloadStarted, Toast.LENGTH_SHORT).show()
                            },
                            size = 44.dp,
                            iconSize = 22.dp,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                LargeTitleHeader(title = title)
                if (songs.isNotEmpty() || (isDownloads && pending.isNotEmpty())) {
                    val count = pluralStringResource(R.plurals.track_count, songs.size, songs.size)
                    Text(
                        if (isDownloads && usedBytes > 0) "$count · ${Formatter.formatShortFileSize(context, usedBytes)}" else count,
                        color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                if (songs.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GlassButton(stringResource(R.string.play_all), { onPlay(songs, 0, false) }, Modifier.weight(1f), icon = Icons.Default.PlayArrow)
                        GlassButton(stringResource(R.string.shuffle), { onPlay(songs, songs.indices.random(), true) }, Modifier.weight(1f), icon = Icons.Default.Shuffle)
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
        if (isDownloads && pending.isNotEmpty()) {
            item(key = "pending-title") {
                Text(
                    stringResource(R.string.downloads_in_progress),
                    color = VyoraText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
            }
            val keys0 = com.vyora.music.ui.design.uniqueKeys(pending) { "p-" + it.item.id }
            itemsIndexed(pending, key = { i, _ -> keys0[i] }) { _, entry ->
                PendingDownloadRow(entry)
            }
            item(key = "pending-gap") { Spacer(Modifier.height(18.dp)) }
        }
        if (songs.isEmpty() && !(isDownloads && pending.isNotEmpty())) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp)) {
                    GlassEmptyState(
                        icon = if (isDownloads) Icons.Default.FileDownload else Icons.Default.MusicNote,
                        title = stringResource(if (isDownloads) R.string.downloads_empty_title else R.string.collection_empty_title),
                        body = stringResource(if (isDownloads) R.string.downloads_empty_body else R.string.collection_empty_body),
                    )
                }
            }
        } else {
            val keys1 = com.vyora.music.ui.design.uniqueKeys(songs) { it.id }
            itemsIndexed(songs, key = { i, _ -> keys1[i] }) { i, item ->
                ItemRow(item = item, onClick = { onPlay(songs, i, false) }, showSeparator = i < songs.lastIndex)
            }
        }
    }
}

@Composable
private fun PendingDownloadRow(entry: DownloadEntry) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.item.thumbnail,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.item.title, color = VyoraText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            when (entry.status) {
                DownloadStatus.FAILED -> Text(
                    stringResource(R.string.download_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                DownloadStatus.QUEUED -> Text(
                    stringResource(R.string.download_waiting),
                    color = VyoraTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = VyoraAccent,
                        trackColor = VyoraGroupedCell,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${(entry.progress * 100).toInt()}%", color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (entry.status == DownloadStatus.FAILED) {
            GlassIconButton(Icons.Default.Refresh, stringResource(R.string.download_retry), { Downloads.download(context, entry.item) }, size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        GlassIconButton(Icons.Default.Close, stringResource(R.string.download_cancel), { Downloads.remove(context, entry.item.id) }, size = 36.dp, iconSize = 18.dp)
    }
}
