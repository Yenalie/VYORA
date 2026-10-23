package com.vyora.music.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.ArrowBackIosNew
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.library.UserPlaylist
import com.vyora.music.data.library.primaryArtist
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.GlassMenu
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.components.MenuAction
import com.vyora.music.ui.components.PlaylistCover
import com.vyora.music.ui.components.PlaylistNameDialog
import com.vyora.music.ui.design.Artwork
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.Hairline
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.screens.settings.ConfirmDialog
import com.vyora.music.ui.theme.GlassFillStrong
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted

object LibraryPages {
    const val PLAYLISTS = "library/playlists"
    const val ALBUMS = "library/albums"
    const val ARTISTS = "library/artists"
    const val LOCAL = "library/local"
    const val RECAP = "library/recap"
    const val SERVER = "library/server"
    const val IMPORT = "library/import"
    fun serverAlbum(item: com.vyora.music.data.youtube.SimpleItem) =
        "server/album/" + android.net.Uri.encode(item.id) + "?title=" + android.net.Uri.encode(item.title) +
            "&artist=" + android.net.Uri.encode(item.artist) + "&art=" + android.net.Uri.encode(item.thumbnail.orEmpty())
    fun playlist(id: String) = "userplaylist/$id"
}

@Composable
private fun PushedListPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val bottomInset = LocalBottomBarInset.current
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 28.dp + bottomInset),
    ) {
        item(key = "top") {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack, size = 44.dp, iconSize = 20.dp)
                    Spacer(Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.height(14.dp))
                LargeTitleHeader(title = title, subtitle = subtitle)
                Spacer(Modifier.height(10.dp))
            }
        }
        content()
    }
}

@Composable
fun PlaylistsScreen(onBack: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val playlists by LibraryManager.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    PushedListPage(title = stringResource(R.string.library_playlists), onBack = onBack) {
        item(key = "new") {
            LibraryListRow(
                leading = {
                    Box(Modifier.size(56.dp).background(GlassFillStrong, GlassShapes.Small), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, null, tint = VyoraAccent, modifier = Modifier.size(28.dp))
                    }
                },
                title = stringResource(R.string.playlist_new_ellipsis),
                titleColor = VyoraAccent,
                onClick = { creating = true },
                separator = playlists.isNotEmpty(),
            )
        }
        itemsIndexed(playlists, key = { _, it -> it.id }) { i, p ->
            LibraryListRow(
                leading = { PlaylistCover(p, 56.dp) },
                title = p.name,
                subtitle = pluralStringResource(R.plurals.track_count, p.songs.size, p.songs.size) +
                    if (p.isSynced) " · " + stringResource(R.string.playlist_on_youtube) else "",
                onClick = { onOpenPlaylist(p.id) },
                separator = i < playlists.lastIndex,
            )
        }
    }
    if (creating) {
        val fallbackName = stringResource(R.string.kind_playlist)
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                onOpenPlaylist(LibraryManager.createPlaylist(name, fallbackName))
            },
        )
    }
}

@Composable
fun UserPlaylistScreen(
    id: String,
    onBack: () -> Unit,
    onPlay: (List<SimpleItem>, Int, Boolean) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val playlists by LibraryManager.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val bottomInset = LocalBottomBarInset.current

    if (playlist == null) {
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(14.dp)) {
            GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack, size = 44.dp, iconSize = 20.dp)
        }
        return
    }
    val songs = playlist.songs
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadAllMsg = stringResource(R.string.toast_download_all_started)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 28.dp + bottomInset),
    ) {
        item(key = "top") {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack, size = 44.dp, iconSize = 20.dp)
                    Spacer(Modifier.weight(1f))
                    Box {
                        GlassIconButton(Icons.Default.MoreHoriz, stringResource(R.string.more), { menu = true }, size = 44.dp, iconSize = 22.dp)
                        GlassMenu(
                            expanded = menu,
                            onDismiss = { menu = false },
                            actions = listOfNotNull(
                                if (songs.isNotEmpty()) MenuAction(stringResource(R.string.download_all), Icons.Default.FileDownload) {
                                    com.vyora.music.data.download.Downloads.downloadAll(context, songs)
                                    android.widget.Toast.makeText(context, downloadAllMsg, android.widget.Toast.LENGTH_SHORT).show()
                                } else null,
                                MenuAction(stringResource(R.string.playlist_rename), Icons.Default.Edit) { renaming = true },
                                MenuAction(stringResource(R.string.playlist_delete), Icons.Default.Delete, accent = true) { deleting = true },
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PlaylistCover(playlist, 240.dp)
                Spacer(Modifier.height(16.dp))
                Text(playlist.name, color = VyoraText, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.track_count, songs.size, songs.size),
                    color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlassButton(stringResource(R.string.play_all), { onPlay(songs, 0, false) }, Modifier.weight(1f), icon = Icons.Default.PlayArrow, enabled = songs.isNotEmpty())
                    GlassButton(stringResource(R.string.shuffle), { onPlay(songs, songs.indices.random(), true) }, Modifier.weight(1f), icon = Icons.Default.Shuffle, enabled = songs.isNotEmpty())
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        if (songs.isEmpty()) {
            item(key = "empty") {
                GlassEmptyState(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    title = stringResource(R.string.playlist_empty_title),
                    body = stringResource(R.string.playlist_empty_body),
                    actionLabel = stringResource(R.string.home_empty_action),
                    onAction = onOpenSearch,
                )
            }
        } else {
            val keys0 = com.vyora.music.ui.design.uniqueKeys(songs) { it.id }
            itemsIndexed(songs, key = { i, _ -> keys0[i] }) { i, item ->
                ItemRow(item = item, onClick = { onPlay(songs, i, false) }, showSeparator = i < songs.lastIndex, playlistId = playlist.id)
            }
        }
    }

    if (renaming) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            initial = playlist.name,
            onDismiss = { renaming = false },
            onConfirm = { LibraryManager.renamePlaylist(playlist.id, it); renaming = false },
        )
    }
    if (deleting) {
        ConfirmDialog(
            message = stringResource(R.string.playlist_delete_confirm, playlist.name),
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                onBack()
                LibraryManager.deletePlaylist(playlist.id)
            },
        )
    }
}

@Composable
fun AlbumsScreen(onBack: () -> Unit, onOpen: (SimpleItem) -> Unit) {
    val saved by LibraryManager.savedCollections.collectAsState()
    PushedListPage(title = stringResource(R.string.library_albums), onBack = onBack) {
        if (saved.isEmpty()) {
            item(key = "empty") {
                GlassEmptyState(
                    icon = Icons.Default.Album,
                    title = stringResource(R.string.library_albums_empty_title),
                    body = stringResource(R.string.library_albums_empty_body),
                )
            }
        } else {
            val rows = saved.chunked(2)
            items(rows.size, key = { "row_$it" }) { r ->
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    rows[r].forEach { item ->
                        CollectionTile(item, Modifier.weight(1f), width = Dp.Unspecified) { onOpen(item) }
                    }
                    if (rows[r].size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

data class LibraryArtist(val name: String, val art: String?, val id: String?)

fun libraryArtists(liked: List<SimpleItem>, playlists: List<UserPlaylist>, saved: List<SimpleItem>): List<LibraryArtist> {
    val art = LinkedHashMap<String, LibraryArtist>()
    (liked + playlists.flatMap { it.songs } + saved).forEach { item ->
        val name = primaryArtist(item.artist)
        if (name.isBlank()) return@forEach
        val known = art[name]
        if (known == null) art[name] = LibraryArtist(name, item.thumbnail, item.artistId)
        else if (known.id == null && item.artistId != null) art[name] = known.copy(id = item.artistId)
    }
    return art.values.sortedBy { it.name.lowercase() }
}

@Composable
fun ArtistsScreen(onBack: () -> Unit, onOpenArtist: (String, String?, String?) -> Unit) {
    val liked by LibraryManager.likedSongs.collectAsState()
    val playlists by LibraryManager.playlists.collectAsState()
    val saved by LibraryManager.savedCollections.collectAsState()
    val artists = remember(liked, playlists, saved) { libraryArtists(liked, playlists, saved) }
    PushedListPage(title = stringResource(R.string.library_artists), onBack = onBack) {
        if (artists.isEmpty()) {
            item(key = "empty") {
                GlassEmptyState(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.library_artists_empty_title),
                    body = stringResource(R.string.library_artists_empty_body),
                )
            }
        } else {
            val keys1 = com.vyora.music.ui.design.uniqueKeys(artists) { it.name }
            itemsIndexed(artists, key = { i, _ -> keys1[i] }) { i, (name, art, id) ->
                LibraryListRow(
                    leading = {
                        Artwork(art, Modifier.size(56.dp), shape = CircleShape, placeholder = Icons.Default.Person, contentDescription = name)
                    },
                    title = name,
                    onClick = { onOpenArtist(name, null, id) },
                    separator = i < artists.lastIndex,
                )
            }
        }
    }
}

@Composable
fun LibraryListRow(
    leading: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    titleColor: androidx.compose.ui.graphics.Color = VyoraText,
    separator: Boolean = true,
) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .glassClickable(pressedScale = 0.985f, onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = VyoraTextFaint, modifier = Modifier.size(22.dp))
        }
        if (separator) Hairline(Modifier.align(Alignment.BottomStart), startIndent = 70.dp)
    }
}
