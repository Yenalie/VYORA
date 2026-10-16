package com.vyora.music.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.download.DownloadStatus
import com.vyora.music.data.download.Downloads
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeConfig
import com.vyora.music.playback.PlayerManager
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.Hairline
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.liquidFill
import com.vyora.music.ui.glass.LocalAppBackdrop
import com.vyora.music.ui.glass.backdrop.Backdrop
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraScrim
import com.vyora.music.ui.theme.VyoraSurfaceElevated
import com.vyora.music.ui.theme.VyoraText

data class MenuAction(
    val label: String,
    val icon: ImageVector,
    val accent: Boolean = false,
    val onClick: () -> Unit,
)

class MenuRequest(
    val anchor: Rect,
    val backdrop: Backdrop,
    val actions: List<MenuAction>,
    val onDismiss: () -> Unit,
)

@Stable
class GlassMenuState {
    var request by mutableStateOf<MenuRequest?>(null)
        private set

    fun show(request: MenuRequest) {
        this.request?.onDismiss?.invoke()
        this.request = request
    }

    fun dismiss() {
        request?.onDismiss?.invoke()
        request = null
    }
}

val LocalGlassMenu = staticCompositionLocalOf { GlassMenuState() }

val LocalMenuBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun BoxScope.GlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<MenuAction>,
) {
    val host = LocalGlassMenu.current
    val backdrop = LocalMenuBackdrop.current ?: LocalAppBackdrop.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Spacer(Modifier.matchParentSize().onGloballyPositioned { bounds = it.boundsInWindow() })
    LaunchedEffect(expanded) {
        if (expanded) host.show(MenuRequest(bounds, backdrop, actions, onDismiss))
    }
}

@Composable
fun BoxScope.SongMenu(
    item: SimpleItem,
    expanded: Boolean,
    onDismiss: () -> Unit,
    playlistId: String? = null,
    extraActions: List<MenuAction> = emptyList(),
) {
    val context = LocalContext.current
    val app = LocalAppActions.current
    val addToPlaylist = stringResource(R.string.add_to_playlist_ellipsis)
    val goToArtist = stringResource(R.string.go_to_artist)
    val removeFromPlaylist = stringResource(R.string.playlist_remove_song)
    val artist = com.vyora.music.data.library.primaryArtist(item.artist)
    val liked by LibraryManager.likedSongs.collectAsState()
    val isLiked = liked.any { it.id == item.id }
    val playNextMsg = stringResource(R.string.toast_play_next)
    val playLastMsg = stringResource(R.string.toast_play_last)
    val shareTitle = stringResource(R.string.share_song)
    val playNext = stringResource(R.string.play_next)
    val playLast = stringResource(R.string.play_last)
    val favorite = stringResource(if (isLiked) R.string.unfavorite else R.string.favorite)
    val createStation = stringResource(R.string.create_station)
    val downloads by Downloads.entries.collectAsState()
    val downloadStatus = downloads[item.id]?.status
    val downloadLabel = stringResource(
        when (downloadStatus) {
            null -> R.string.download
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> R.string.download_cancel
            DownloadStatus.COMPLETED -> R.string.download_remove
            DownloadStatus.FAILED -> R.string.download_retry
        }
    )
    val downloadStarted = stringResource(R.string.toast_download_started)
    val downloadIcon = when (downloadStatus) {
        DownloadStatus.COMPLETED -> Icons.Default.DownloadDone
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> Icons.Default.Downloading
        else -> Icons.Default.FileDownload
    }

    val actions = listOfNotNull(
        MenuAction(addToPlaylist, Icons.AutoMirrored.Filled.PlaylistAdd) { app.addToPlaylist(item) },
        playlistId?.let { pid ->
            MenuAction(removeFromPlaylist, Icons.Default.RemoveCircleOutline) { LibraryManager.removeFromPlaylist(pid, item.id) }
        },
        MenuAction(playNext, Icons.AutoMirrored.Filled.PlaylistPlay) {
            PlayerManager.playNext(item)
            Toast.makeText(context, playNextMsg, Toast.LENGTH_SHORT).show()
        },
        MenuAction(playLast, Icons.AutoMirrored.Filled.QueueMusic) {
            PlayerManager.addToQueue(item)
            Toast.makeText(context, playLastMsg, Toast.LENGTH_SHORT).show()
        },
        MenuAction(favorite, if (isLiked) Icons.Default.Star else Icons.Default.StarBorder, accent = isLiked) {
            LibraryManager.toggleLike(item)
        },
        artist.takeIf { it.isNotBlank() && !item.isLocal }?.let { name ->
            MenuAction(goToArtist, Icons.Default.Person) { app.openArtist(name, null, item.artistId) }
        },
        if (!item.isLocal) MenuAction(createStation, Icons.Default.Radio) { PlayerManager.startRadio(item) } else null,
        if (item.isSong && !item.isLocal) {
            MenuAction(downloadLabel, downloadIcon, accent = downloadStatus == DownloadStatus.COMPLETED) {
                when (downloadStatus) {
                    null, DownloadStatus.FAILED -> {
                        Downloads.download(context, item)
                        Toast.makeText(context, downloadStarted, Toast.LENGTH_SHORT).show()
                    }
                    else -> Downloads.remove(context, item.id)
                }
            }
        } else null,
        if (!item.isLocal) MenuAction(shareTitle, Icons.Default.IosShare) { shareSong(context, item, shareTitle) } else null,
    ) + extraActions
    GlassMenu(expanded = expanded, onDismiss = onDismiss, actions = actions)
}

fun shareSong(context: android.content.Context, item: SimpleItem, chooserTitle: String) {
    val url = "${YouTubeConfig.MUSIC_ORIGIN}/watch?v=${item.id}"
    val text = listOf(item.title, item.artist).filter { it.isNotBlank() }.joinToString(" · ") + "\n" + url
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private val MenuWidth = 250.dp
private val MenuShape = GlassShapes.Large

@Composable
fun GlassMenuHost(state: GlassMenuState) {
    val request = state.request
    var last by remember { mutableStateOf<MenuRequest?>(null) }
    if (request != null) last = request
    val progress by animateFloatAsState(
        targetValue = if (request != null) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "menuProgress",
    )
    val shown = last ?: return
    if (request == null && progress < 0.01f) return

    BackHandler(enabled = request != null) { state.dismiss() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val gap = with(density) { 8.dp.toPx() }
        val menuW = with(density) { MenuWidth.toPx() }
        var menuH by remember { mutableIntStateOf(0) }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
                .background(VyoraScrim)
                .pointerInput(Unit) { detectTapGestures { state.dismiss() } }
        )

        val anchor = shown.anchor
        val alignRight = anchor.center.x > screenW / 2f
        val x = (if (alignRight) anchor.right - menuW else anchor.left).coerceIn(margin, (screenW - menuW - margin).coerceAtLeast(margin))
        val fitsBelow = anchor.bottom + gap + menuH < screenH - margin * 6
        val rawY = if (fitsBelow) anchor.bottom + gap else anchor.top - gap - menuH
        val y = rawY.coerceIn(margin * 4, (screenH - menuH - margin * 6).coerceAtLeast(margin * 4))

        CompositionLocalProvider(LocalAppBackdrop provides shown.backdrop) {
            Column(
                Modifier
                    .offset { IntOffset(x.toInt(), y.toInt()) }
                    .width(MenuWidth)
                    .onSizeChanged { menuH = it.height }
                    .graphicsLayer {
                        val p = progress
                        alpha = p.coerceIn(0f, 1f)
                        val s = 0.6f + 0.4f * p
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(if (alignRight) 1f else 0f, if (fitsBelow) 0f else 1f)
                    }
                    .liquidFill(shape = MenuShape, fill = VyoraSurfaceElevated.copy(alpha = 0.55f), overlay = true)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(vertical = 4.dp),
            ) {
                shown.actions.forEachIndexed { i, action ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .glassClickable(pressedScale = 0.97f) {
                                action.onClick()
                                state.dismiss()
                            }
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            action.label,
                            color = VyoraText,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(action.icon, null, tint = if (action.accent) VyoraAccent else VyoraText, modifier = Modifier.size(20.dp))
                    }
                    if (i < shown.actions.lastIndex) Hairline(Modifier.padding(horizontal = 0.dp))
                }
            }
        }
    }
}
