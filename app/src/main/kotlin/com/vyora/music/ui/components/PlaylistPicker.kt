package com.vyora.music.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.library.UserPlaylist
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.Hairline
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.theme.GlassFillStrong
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted

@Stable
class AppActions(
    val openArtist: (name: String, art: String?, id: String?) -> Unit = { _, _, _ -> },
    val addToPlaylist: (SimpleItem) -> Unit = {},
)

val LocalAppActions = staticCompositionLocalOf { AppActions() }

@Stable
class PlaylistPickerState {
    var pending by mutableStateOf<SimpleItem?>(null)
        private set

    fun open(item: SimpleItem) { pending = item }
    fun close() { pending = null }
}

private val DialogShape = RoundedCornerShape(26.dp)

@Composable
fun PlaylistPickerHost(state: PlaylistPickerState) {
    val item = state.pending ?: return
    val context = LocalContext.current
    val playlists by LibraryManager.playlists.collectAsState()
    var creating by remember(item) { mutableStateOf(false) }
    val addedMsg = stringResource(R.string.playlist_added_toast)
    val fallbackName = stringResource(R.string.kind_playlist)

    if (creating) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                LibraryManager.createPlaylist(name, fallbackName, item)
                Toast.makeText(context, addedMsg.format(name.trim().ifBlank { fallbackName }), Toast.LENGTH_SHORT).show()
                state.close()
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = state::close,
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        shape = DialogShape,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item(key = "new") {
                    PickerRow(
                        leading = {
                            Box(
                                Modifier.size(48.dp).clip(GlassShapes.Small).background(GlassFillStrong),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.Add, null, tint = VyoraAccent, modifier = Modifier.size(24.dp)) }
                        },
                        title = stringResource(R.string.playlist_new_ellipsis),
                        titleColor = VyoraAccent,
                        subtitle = null,
                        onClick = { creating = true },
                    )
                }
                items(playlists, key = { it.id }) { p ->
                    Hairline(startIndent = 62.dp)
                    PickerRow(
                        leading = { PlaylistCover(p, 48.dp) },
                        title = p.name,
                        subtitle = pluralStringResource(R.plurals.track_count, p.songs.size, p.songs.size),
                        onClick = {
                            LibraryManager.addToPlaylist(p.id, item)
                            Toast.makeText(context, addedMsg.format(p.name), Toast.LENGTH_SHORT).show()
                            state.close()
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = state::close) { Text(stringResource(R.string.cancel), color = VyoraAccent) }
        },
    )
}

@Composable
private fun PickerRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    titleColor: Color = VyoraText,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
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
    }
}

@Composable
fun PlaylistNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        shape = DialogShape,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.playlist_name_hint), color = VyoraTextFaint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
                shape = GlassShapes.Medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = VyoraText,
                    unfocusedTextColor = VyoraText,
                    cursorColor = VyoraAccent,
                    focusedBorderColor = VyoraAccent,
                    unfocusedBorderColor = VyoraTextFaint,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.done), color = if (name.isNotBlank()) VyoraAccent else VyoraTextFaint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = VyoraText) }
        },
    )
}

@Composable
fun PlaylistCover(playlist: UserPlaylist, size: Dp, modifier: Modifier = Modifier) {
    val covers = playlist.covers
    Box(
        modifier.size(size).clip(if (size > 80.dp) GlassShapes.Medium else GlassShapes.Small).background(GlassFillStrong),
        contentAlignment = Alignment.Center,
    ) {
        when {
            covers.isEmpty() -> Icon(
                Icons.AutoMirrored.Filled.QueueMusic, null,
                tint = VyoraTextMuted, modifier = Modifier.size(size * 0.42f),
            )
            covers.size < 4 -> AsyncImage(covers.first(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                covers.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f)) {
                        row.forEach { url ->
                            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
