package com.vyora.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.playback.PlayerManager
import com.vyora.music.ui.design.Artwork
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.Hairline
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.artworkKey
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.sharedArtwork
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraHighlight
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted

/**
 * iOS 26 Apple Music style list row for any [SimpleItem]. Deliberately NOT a glass card:
 * in iOS 26 Liquid Glass is reserved for navigation and controls (tab bar, mini player,
 * buttons, menus), while content lists stay plain so the artwork carries the colour.
 *
 * Layout: 48dp artwork with small continuous corners, title + secondary line, trailing
 * "⋯" (songs) or chevron (albums/playlists/artists), and an inset hairline separator that
 * starts at the text, like UITableView. Pressing shows the flat grey cell highlight iOS
 * uses instead of scaling. Long press opens the glass song menu.
 *
 * The playing song gets the Apple Music treatment: an accent EQ glyph in place of the
 * track number, or a dimmed artwork with a white EQ glyph on top.
 *
 * [index] replaces the artwork with a track number (album / playlist track lists).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    item: SimpleItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showSeparator: Boolean = true,
    contentColor: Color = VyoraText,
    secondaryColor: Color = VyoraTextMuted,
    /** Inside a VYORA playlist the song menu offers "Remove from Playlist". */
    playlistId: String? = null,
) {
    val current by PlayerManager.currentItem.collectAsState()
    val isSong = item.isSong
    val isCurrent = isSong && current?.id == item.id
    val isArtist = item.type == ItemType.ARTIST
    var menu by remember { mutableStateOf(false) }

    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val highlight by animateColorAsState(
        targetValue = if (pressed) VyoraHighlight else Color.Transparent,
        // iOS cells highlight instantly on touch and fade out on release.
        animationSpec = tween(durationMillis = if (pressed) 0 else 220),
        label = "rowHighlight",
    )

    val leadingWidth = if (index != null) 32.dp else if (isArtist) 56.dp else Spacing.RowArt
    val textStart = if (index != null) leadingWidth else leadingWidth + 14.dp

    Box(
        modifier
            .fillMaxWidth()
            .clip(RowHighlightShape)
            .background(highlight)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onLongClick = if (isSong) ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }) else null,
                onClick = onClick,
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (index != null) 52.dp else 64.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(Modifier.width(leadingWidth), contentAlignment = Alignment.CenterStart) {
                    if (isCurrent) {
                        Icon(Icons.Default.GraphicEq, null, tint = VyoraAccent, modifier = Modifier.size(18.dp))
                    } else {
                        Text(index.toString(), color = secondaryColor, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                }
            } else {
                Box(Modifier.size(leadingWidth), contentAlignment = Alignment.Center) {
                    Artwork(
                        url = item.thumbnail,
                        shape = if (isArtist) CircleShape else GlassShapes.Small,
                        placeholder = when (item.type) {
                            ItemType.ARTIST -> Icons.Default.Person
                            ItemType.ALBUM -> Icons.Default.Album
                            ItemType.PLAYLIST -> Icons.AutoMirrored.Filled.QueueMusic
                            else -> Icons.Default.MusicNote
                        },
                        contentDescription = item.title,
                        // Album / playlist covers fly into their page when opened.
                        modifier = Modifier
                            .sharedArtwork(if (item.isCollection) artworkKey(item.id) else null)
                            .fillMaxSize(),
                    )
                    if (isCurrent) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(GlassShapes.Small)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // iOS style subtitles: "Artist", "Album · Tulus", "Playlist · Someone".
                val kindAlbum = stringResource(R.string.kind_album)
                val kindPlaylist = stringResource(R.string.kind_playlist)
                val meta = when (item.type) {
                    ItemType.ARTIST -> stringResource(R.string.kind_artist)
                    ItemType.ALBUM -> listOf(kindAlbum, item.artist).filter { it.isNotBlank() }.joinToString(" · ")
                    ItemType.PLAYLIST -> listOf(kindPlaylist, item.artist).filter { it.isNotBlank() }.joinToString(" · ")
                    else -> item.artist.takeIf { it.isNotBlank() }
                }
                if (meta != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = secondaryColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isSong) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            // 48dp: the Android minimum touch target (the glyph stays 20dp).
                            .size(48.dp)
                            .glassClickable(pressedScale = 0.85f) { menu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.MoreHoriz, stringResource(R.string.more), tint = contentColor, modifier = Modifier.size(20.dp))
                    }
                    SongMenu(item = item, expanded = menu, onDismiss = { menu = false }, playlistId = playlistId)
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = VyoraTextFaint, modifier = Modifier.padding(start = 6.dp, end = 4.dp).size(22.dp),
                )
            }
        }
        // Inset hairline aligned with the title, hidden under the last row.
        if (showSeparator) {
            Hairline(Modifier.align(Alignment.BottomStart), startIndent = textStart)
        }
    }
}

/** Slight rounding so the press highlight doesn't look like a hard block inside padded lists. */
private val RowHighlightShape = RoundedCornerShape(10.dp)

/** 215 -> "3:35", 3725 -> "1:02:05". */
fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
