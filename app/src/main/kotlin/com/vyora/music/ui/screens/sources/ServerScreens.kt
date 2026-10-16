package com.vyora.music.ui.screens.sources

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.local.AudioTier
import com.vyora.music.data.server.MusicServer
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.components.QualityBadge
import com.vyora.music.ui.components.VyoraTextField
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.SectionTitle
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.screens.CollectionTile
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlinx.coroutines.launch

private fun MusicServer.ServerSong.tier(): AudioTier? = when {
    !lossless -> null
    bitDepth > 16 || sampleRate > 48_000 -> AudioTier.HI_RES_LOSSLESS
    else -> AudioTier.LOSSLESS
}

@Composable
private fun SourcePage(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val bottomInset = LocalBottomBarInset.current
    LazyColumn(
        Modifier.fillMaxSize(),
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
                Spacer(Modifier.height(14.dp))
            }
        }
        content()
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = VyoraAccent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

/** Your own music server: connect once, then browse, search and stream the original lossless files. */
@Composable
fun ServerScreen(
    onBack: () -> Unit,
    onOpenAlbum: (SimpleItem) -> Unit,
    onPlay: (List<SimpleItem>, Int, Boolean) -> Unit,
) {
    val config by MusicServer.config.collectAsState()
    val cfg = config
    if (cfg == null) {
        ServerConnect(onBack)
        return
    }
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<List<SimpleItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<MusicServer.SearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(cfg) {
        MusicServer.albums("newest").onSuccess { albums = it }.onFailure { error = it.message }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        searching = true
        results = MusicServer.search(query.trim()).getOrNull()
        searching = false
    }

    SourcePage(
        title = stringResource(R.string.server_title),
        subtitle = cfg.name.ifBlank { cfg.baseUrl.host },
        onBack = onBack,
        trailing = {
            GlassIconButton(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.server_disconnect), { MusicServer.disconnect() }, size = 44.dp, iconSize = 20.dp)
        },
    ) {
        item(key = "actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(stringResource(R.string.server_shuffle_all), {
                    scope.launch {
                        val songs = MusicServer.randomSongs(80).getOrNull().orEmpty().map { it.item }
                        if (songs.isNotEmpty()) onPlay(songs, 0, false)
                    }
                }, Modifier.weight(1f), icon = Icons.Default.Shuffle)
            }
            Spacer(Modifier.height(12.dp))
            VyoraTextField(query, { query = it }, stringResource(R.string.server_search), placeholder = stringResource(R.string.server_search_hint))
            Spacer(Modifier.height(8.dp))
        }
        val r = results
        if (query.isNotBlank()) {
            if (searching && r == null) item(key = "searching") { Loading() }
            if (r != null) {
                if (r.albums.isNotEmpty()) item(key = "r_albums") {
                    SectionTitle(stringResource(R.string.library_albums))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(r.albums, key = { it.id }) { a -> CollectionTile(a, width = 140.dp) { onOpenAlbum(a) } }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                val songs = r.songs.map { it.item }
                itemsIndexed(r.songs, key = { _, s -> "s_" + s.item.id }) { i, s ->
                    ItemRow(
                        item = s.item, onClick = { onPlay(songs, i, false) }, showSeparator = i < r.songs.lastIndex,
                        badge = s.tier()?.let { t -> { QualityBadge(t) } },
                    )
                }
                if (r.songs.isEmpty() && r.albums.isEmpty()) item(key = "none") {
                    Text(stringResource(R.string.search_empty_title), color = VyoraTextMuted, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
        } else {
            item(key = "new_title") {
                Spacer(Modifier.height(8.dp))
                SectionTitle(stringResource(R.string.server_recent_albums))
                Spacer(Modifier.height(10.dp))
            }
            val list = albums
            when {
                error != null -> item(key = "err") {
                    GlassEmptyState(Icons.Default.Dns, stringResource(R.string.server_error_title), error.orEmpty())
                }
                list == null -> item(key = "loading") { Loading() }
                list.isEmpty() -> item(key = "empty") {
                    GlassEmptyState(Icons.Default.GraphicEq, stringResource(R.string.server_empty_title), stringResource(R.string.server_empty_body))
                }
                else -> items(list.chunked(2), key = { row -> row.first().id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { a -> CollectionTile(a, Modifier.weight(1f), width = androidx.compose.ui.unit.Dp.Unspecified) { onOpenAlbum(a) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerConnect(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.server_connect_failed)
    SourcePage(stringResource(R.string.server_title), stringResource(R.string.server_subtitle), onBack) {
        item(key = "form") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.server_intro), color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
                VyoraTextField(url, { url = it; error = null }, stringResource(R.string.server_url), placeholder = "https://music.example.com", keyboardType = KeyboardType.Uri)
                VyoraTextField(user, { user = it; error = null }, stringResource(R.string.server_user))
                VyoraTextField(pass, { pass = it; error = null }, stringResource(R.string.server_password), password = true, error = error)
                Spacer(Modifier.height(4.dp))
                if (busy) Loading() else GlassButton(
                    stringResource(R.string.server_connect),
                    {
                        busy = true
                        scope.launch {
                            MusicServer.connect(url, user, pass).onFailure { error = failed + (it.message?.let { m -> " ($m)" } ?: "") }
                            busy = false
                        }
                    },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Default.Dns,
                    enabled = url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                )
                Text(stringResource(R.string.server_privacy), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ServerAlbumScreen(
    album: SimpleItem,
    onBack: () -> Unit,
    onPlay: (List<SimpleItem>, Int, Boolean) -> Unit,
) {
    var songs by remember { mutableStateOf<List<MusicServer.ServerSong>?>(null) }
    LaunchedEffect(album.id) { songs = MusicServer.albumSongs(album.id).getOrNull().orEmpty() }
    val list = songs
    val items = remember(list) { list.orEmpty().map { it.item } }
    SourcePage(album.title, album.artist.ifBlank { null }, onBack) {
        if (list == null) {
            item(key = "loading") { Loading() }
        } else {
            item(key = "play") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton(stringResource(R.string.play_all), { onPlay(items, 0, false) }, Modifier.weight(1f), icon = Icons.Default.PlayArrow, enabled = items.isNotEmpty())
                    GlassButton(stringResource(R.string.shuffle), { onPlay(items, items.indices.random(), true) }, Modifier.weight(1f), icon = Icons.Default.Shuffle, enabled = items.isNotEmpty())
                }
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(list, key = { _, s -> s.item.id }) { i, s ->
                ItemRow(
                    item = s.item, index = i + 1, onClick = { onPlay(items, i, false) }, showSeparator = i < list.lastIndex,
                    badge = s.tier()?.let { t -> { QualityBadge(t) } },
                )
            }
        }
    }
}
