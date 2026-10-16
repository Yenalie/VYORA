package com.vyora.music.ui.player

import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.CastConnected
import com.vyora.music.playback.cast.CastSupport
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.lyrics.LyricLine
import com.vyora.music.data.lyrics.Lyrics
import com.vyora.music.data.lyrics.LyricsRepository
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.playback.PlayerManager
import com.vyora.music.playback.QueueEntry
import com.vyora.music.playback.SleepTimer
import com.vyora.music.playback.video.VideoArtworkPlayer
import com.vyora.music.ui.components.GlassMenu
import com.vyora.music.ui.components.LocalMenuBackdrop
import com.vyora.music.ui.components.MenuAction
import com.vyora.music.ui.components.SongMenu
import com.vyora.music.ui.design.Artwork
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.VideoAmbience
import com.vyora.music.ui.design.glass
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.liquidFill
import com.vyora.music.ui.design.rememberArtworkTint
import com.vyora.music.ui.glass.LocalAppBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.layerBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.vyora.music.ui.theme.VyoraBackground
import com.vyora.music.ui.theme.VyoraAccent
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val SheetTopCorner = 28.dp

@Composable
fun VyoraPlayerSheet(
    sheet: PlayerSheetState,
    modifier: Modifier = Modifier,
) {
    val title by PlayerManager.currentTitle.collectAsState()
    if (title == null) return

    val visible = sheet.isVisible
    val tracker = remember { VelocityTracker() }
    val flingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 700.dp.toPx() }
    var heightPx by remember { mutableIntStateOf(1) }
    val flight = LocalArtworkFlight.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .sheetContainer(flight)
            .then(
                if (visible) {
                    Modifier
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { tracker.resetTracking() },
                                onVerticalDrag = { change, dy ->
                                    tracker.addPointerInputChange(change)
                                    sheet.dragBy(-dy / heightPx)
                                },
                                onDragEnd = { sheet.settle(tracker.calculateVelocity().y, heightPx, flingPx) },
                                onDragCancel = { sheet.settle(0f, heightPx, flingPx) },
                            )
                        }
                        .pointerInput(Unit) { detectTapGestures { } }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = sheet.progress.value
                    translationY = (1f - p) * size.height
                    val corner = SheetTopCorner.toPx() * (1f - p)
                    shape = RoundedCornerShape(topStart = corner, topEnd = corner, bottomEnd = 0f, bottomStart = 0f)
                    clip = true
                }
                .sheetContent(flight)
                .background(VyoraBackground),
        ) {
            FullPlayerContent(sheet)
        }
    }
}

private const val MODE_ART = 0
private const val MODE_LYRICS = 1
private const val MODE_QUEUE = 2

@Composable
private fun FullPlayerContent(sheet: PlayerSheetState) {
    val appSettings by com.vyora.music.data.settings.SettingsRepository.settings.collectAsState()
    val animatedBackground = appSettings.animatedPlayerBackground
    val title by PlayerManager.currentTitle.collectAsState()
    val artist by PlayerManager.currentArtist.collectAsState()
    val thumbnail by PlayerManager.currentThumbnail.collectAsState()
    val isPlaying by PlayerManager.isPlaying.collectAsState()
    val currentItem by PlayerManager.currentItem.collectAsState()
    val likedSongs by LibraryManager.likedSongs.collectAsState()
    val isLiked = currentItem?.let { item -> likedSongs.any { it.id == item.id } } ?: false
    var mode by rememberSaveable { mutableIntStateOf(MODE_ART) }
    var menu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val videoItem = currentItem?.takeIf { it.isVideo && VideoArtworkPlayer.allowed(context, appSettings.videoArtwork) }
    val videoId = videoItem?.id?.takeIf { sheet.isVisible }
    val coverTint by rememberArtworkTint(thumbnail)
    val videoTint by VideoAmbience.tint.collectAsState()
    val videoFrame by VideoAmbience.frame.collectAsState()
    val animatedTint by animateColorAsState(
        targetValue = (if (videoId != null) videoTint else null) ?: coverTint,
        animationSpec = tween(700),
        label = "playerTint",
    )

    val playerBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(playerBackdrop)) {
            LivingBackground(
                thumbnail = thumbnail,
                videoFrame = if (videoId != null) videoFrame else null,
                tint = animatedTint,
                animate = sheet.isVisible && isPlaying && animatedBackground,
            )
        }

        CompositionLocalProvider(LocalAppBackdrop provides playerBackdrop, LocalMenuBackdrop provides playerBackdrop) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(width = 60.dp, height = 22.dp)
                    .glassClickable(pressedScale = 0.9f) { sheet.collapse() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(38.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
            }

            val favorite: () -> Unit = { currentItem?.let { LibraryManager.toggleLike(it) } }
            val actions: @Composable () -> Unit = {
                GlassIconButton(
                    icon = if (isLiked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(if (isLiked) R.string.unfavorite else R.string.favorite),
                    onClick = favorite,
                    size = 40.dp, iconSize = 20.dp,
                    tint = Color.White,
                    fill = Color.White.copy(alpha = 0.16f),
                )
                Spacer(Modifier.width(10.dp))
                Box {
                    GlassIconButton(
                        icon = Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.more),
                        onClick = { menu = true },
                        size = 40.dp, iconSize = 20.dp,
                        tint = Color.White,
                        fill = Color.White.copy(alpha = 0.16f),
                    )
                    val sleepMode by SleepTimer.mode.collectAsState()
                    val sleepLabel = when (val m = sleepMode) {
                        is SleepTimer.Mode.At -> stringResource(R.string.sleep_timer_left, formatTime(SleepTimer.remainingMs() ?: 0L))
                        SleepTimer.Mode.EndOfTrack -> stringResource(R.string.sleep_timer_left, stringResource(R.string.sleep_end_of_song))
                        null -> stringResource(R.string.sleep_timer)
                    }
                    val sleepAction = MenuAction(sleepLabel, Icons.Default.Bedtime, accent = sleepMode != null) { sleepMenu = true }
                    currentItem?.let {
                        SongMenu(item = it, expanded = menu, onDismiss = { menu = false }, extraActions = listOf(sleepAction))
                    }
                    SleepTimerMenu(expanded = sleepMenu, onDismiss = { sleepMenu = false })
                }
            }

            Crossfade(
                targetState = mode,
                animationSpec = tween(260),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "playerMode",
            ) { m ->
                if (m == MODE_ART) {
                    Column(Modifier.fillMaxSize()) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val side = minOf(maxWidth, maxHeight)
                            SwipeArtwork(
                                thumbnail, title, isPlaying, side, sheet,
                                flightEnabled = mode == MODE_ART,
                                wide = videoItem != null,
                                videoId = videoId,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = title.orEmpty(),
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500),
                                )
                                val appActions = com.vyora.music.ui.components.LocalAppActions.current
                                val artistName = com.vyora.music.data.library.primaryArtist(artist.orEmpty())
                                Text(
                                    text = artist.orEmpty(),
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (artistName.isNotBlank()) {
                                        Modifier.glassClickable(pressedScale = 0.97f) { appActions.openArtist(artistName, null, currentItem?.artistId) }
                                    } else Modifier,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            actions()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        CompactHeader(thumbnail, title, artist, onArtwork = { mode = MODE_ART }, actions = actions)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (m == MODE_LYRICS) LyricsPanel() else QueuePanel()
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SeekSection()
            Spacer(Modifier.height(6.dp))
            TransportRow()
            Spacer(Modifier.height(10.dp))
            VolumeSection(active = sheet.isVisible)
            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomToggle(Icons.Default.Lyrics, stringResource(R.string.lyrics), mode == MODE_LYRICS) {
                    mode = if (mode == MODE_LYRICS) MODE_ART else MODE_LYRICS
                }
                val castStatus by CastSupport.status.collectAsState()
                var outputs by remember { mutableStateOf(false) }
                BottomToggle(
                    if (castStatus.connected) Icons.Default.CastConnected else Icons.Default.Airplay,
                    stringResource(R.string.output),
                    castStatus.connected,
                ) {
                    if (castStatus.available) outputs = true else openOutputSwitcher(context)
                }
                if (outputs) OutputPickerDialog(onDismiss = { outputs = false })
                BottomToggle(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue), mode == MODE_QUEUE) {
                    mode = if (mode == MODE_QUEUE) MODE_ART else MODE_QUEUE
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        }
    }
}

@Composable
private fun LivingBackground(thumbnail: String?, videoFrame: ImageBitmap?, tint: Color, animate: Boolean) {
    Box(Modifier.fillMaxSize().background(lerp(tint, Color.Black, 0.45f)))
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val cover: Any? = remember(thumbnail, canBlur) {
        if (canBlur || thumbnail == null) thumbnail
        else coil.request.ImageRequest.Builder(context).data(thumbnail).size(24).build()
    }
    val phase = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            phase.snapTo(phase.value % 360f)
            phase.animateTo(phase.value + 360f, tween(40_000, easing = LinearEasing))
        }
    }
    val group = Modifier.fillMaxSize().clipToBounds().then(if (canBlur) Modifier.blur(90.dp) else Modifier)
    Crossfade(targetState = videoFrame, animationSpec = tween(700), label = "videoGlow") { frame ->
        androidx.compose.foundation.layout.BoxWithConstraints(group, contentAlignment = Alignment.Center) {
            val side = kotlin.math.hypot(maxWidth.value, maxHeight.value).dp * 1.2f
            BackgroundCopy(frame, cover, phase, side, scale = 1.1f, speed = 1f, startAngle = 0f, alpha = 1f)
            BackgroundCopy(frame, cover, phase, side, scale = 0.85f, speed = -1.6f, startAngle = 140f, alpha = 0.6f)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.10f),
                    0.45f to Color.Black.copy(alpha = 0.28f),
                    1f to Color.Black.copy(alpha = 0.58f),
                )
            ),
    )
}

@Composable
private fun BackgroundCopy(
    frame: ImageBitmap?,
    cover: Any?,
    phase: androidx.compose.animation.core.Animatable<Float, *>,
    side: androidx.compose.ui.unit.Dp,
    scale: Float,
    speed: Float,
    startAngle: Float,
    alpha: Float,
) {
    val layer = Modifier
        .requiredSize(side)
        .graphicsLayer {
            val angle = startAngle + phase.value * speed
            val r = Math.toRadians(angle.toDouble())
            scaleX = scale; scaleY = scale
            rotationZ = angle
            translationX = (kotlin.math.sin(r) * size.width * 0.05).toFloat()
            translationY = (kotlin.math.cos(r) * size.height * 0.05).toFloat()
            this.alpha = alpha
        }
    if (frame != null) {
        Image(bitmap = frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = layer)
    } else {
        AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = layer)
    }
}

@Composable
private fun SwipeArtwork(
    thumbnail: String?,
    title: String?,
    isPlaying: Boolean,
    side: androidx.compose.ui.unit.Dp,
    sheet: PlayerSheetState,
    flightEnabled: Boolean,
    wide: Boolean = false,
    videoId: String? = null,
) {
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.82f,
        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow),
        label = "artworkScale",
    )
    val elevation by animateDpAsState(if (isPlaying) 34.dp else 10.dp, label = "artworkElevation")
    var dragX by remember { mutableFloatStateOf(0f) }
    val shownX by animateFloatAsState(dragX, spring(stiffness = Spring.StiffnessMediumLow), label = "artDrag")
    val haptics = LocalHapticFeedback.current
    val sharp = remember(thumbnail) { com.vyora.music.data.youtube.YouTubeClient.artUrl(thumbnail, 1080) }
    val height by animateDpAsState(if (wide) side * 9f / 16f else side, spring(stiffness = Spring.StiffnessMediumLow), label = "artworkHeight")
    Box(
        modifier = Modifier
            .size(width = side, height = height)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dx -> change.consume(); dragX += dx },
                    onDragEnd = {
                        val threshold = size.width * 0.22f
                        when {
                            dragX < -threshold -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); PlayerManager.next() }
                            dragX > threshold -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); PlayerManager.previous() }
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = shownX * 0.6f
                rotationZ = shownX / size.width * 4f
            }
            .fullFlightTarget(sheet, corner = 14.dp, enabled = flightEnabled)
            .shadow(elevation, GlassShapes.Medium, clip = false, ambientColor = Color.Black, spotColor = Color.Black),
    ) {
        Artwork(
            url = sharp,
            placeholderKey = thumbnail,
            contentDescription = title,
            shape = GlassShapes.Medium,
            placeholderSize = 64.dp,
            modifier = Modifier.fillMaxSize(),
        )
        if (videoId != null) {
            key(videoId) {
                VideoArtwork(videoId, Modifier.fillMaxSize().clip(GlassShapes.Medium))
            }
        }
    }
}

@Composable
private fun CompactHeader(
    thumbnail: String?,
    title: String?,
    artist: String?,
    onArtwork: () -> Unit,
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = thumbnail,
            contentDescription = title,
            shape = GlassShapes.Tiny,
            modifier = Modifier
                .size(64.dp)
                .glassClickable(pressedScale = 0.92f, onClick = onArtwork)
                .shadow(10.dp, GlassShapes.Tiny, clip = false),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist.orEmpty(), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        actions()
    }
}

private fun Modifier.edgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent, 0.08f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun LyricsPanel() {
    val item by PlayerManager.currentItem.collectAsState()
    val durationMs by PlayerManager.durationMs.collectAsState()
    val current = item
    if (current == null) return

    var loading by remember(current.id) { mutableStateOf(true) }
    var lyrics by remember(current.id) { mutableStateOf<Lyrics?>(null) }
    var selection by remember(current.id) { mutableStateOf<Set<Int>?>(null) }
    var cardLines by remember(current.id) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(current.id) {
        loading = true
        val seconds = if (current.duration > 0) current.duration else durationMs / 1000
        lyrics = LyricsRepository.lyricsFor(current.id, current.title, current.artist, seconds)
        loading = false
    }

    val lyr = lyrics
    val lines = remember(lyr) {
        when {
            lyr == null -> emptyList()
            lyr.isSynced -> lyr.synced.map { it.text }
            else -> lyr.plain.orEmpty().lines().map { it.trimEnd() }
        }
    }
    val tooMany = stringResource(R.string.lyrics_card_max, LyricsCardRenderer.MAX_LINES)
    val context = LocalContext.current
    fun toggle(i: Int) {
        if (lines.getOrNull(i).isNullOrBlank()) return
        val now = selection.orEmpty()
        selection = when {
            i in now -> (now - i).ifEmpty { null }
            now.size >= LyricsCardRenderer.MAX_LINES -> {
                android.widget.Toast.makeText(context, tooMany, android.widget.Toast.LENGTH_SHORT).show()
                now
            }
            else -> now + i
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading -> CenterNote(stringResource(R.string.lyrics_loading))
            lyr == null || lines.none { it.isNotBlank() } -> CenterNote(stringResource(R.string.lyrics_unavailable))
            lyr.isSynced -> SyncedLyrics(lyr.synced, selection, onLongPress = ::toggle, onToggle = ::toggle)
            else -> PlainLyrics(lines, selection, onLongPress = ::toggle, onToggle = ::toggle)
        }
        if (!loading && lines.any { it.isNotBlank() }) {
            val sel = selection
            if (sel == null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp)
                        .size(36.dp)
                        .glassClickable(pressedScale = 0.86f) { selection = emptySet() }
                        .liquidFill(shape = CircleShape, fill = Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.FormatQuote, stringResource(R.string.lyrics_card_title), tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                }
            } else {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF1C1C1E).copy(alpha = 0.94f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { selection = null }) { Text(stringResource(R.string.cancel), color = Color.White) }
                    Text(
                        if (sel.isEmpty()) stringResource(R.string.lyrics_card_pick)
                        else stringResource(R.string.lyrics_card_selected, sel.size),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = sel.isNotEmpty(),
                        onClick = { cardLines = sel.sorted().mapNotNull { lines.getOrNull(it) }.filter { it.isNotBlank() } },
                    ) { Text(stringResource(R.string.lyrics_card_share), color = if (sel.isNotEmpty()) VyoraAccent else Color.White.copy(alpha = 0.4f)) }
                }
            }
        }
    }
    cardLines?.let { picked ->
        LyricsCardDialog(item = current, lines = picked, onDismiss = {
            cardLines = null
            selection = null
        })
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.lyricGestures(
    index: Int,
    selecting: Boolean,
    onTap: () -> Unit,
    onLongPress: (Int) -> Unit,
    onToggle: (Int) -> Unit,
): Modifier = combinedClickable(
    interactionSource = null,
    indication = null,
    onLongClick = { if (!selecting) onLongPress(index) },
    onClick = { if (selecting) onToggle(index) else onTap() },
)

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    selection: Set<Int>?,
    onLongPress: (Int) -> Unit,
    onToggle: (Int) -> Unit,
) {
    val positionMs by PlayerManager.positionMs.collectAsState()
    val active = remember(lines, positionMs) {
        val t = positionMs + 250
        lines.indexOfLast { it.timeMs <= t }.coerceAtLeast(0)
    }
    val selecting = selection != null
    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) userScrolling = true
        else { kotlinx.coroutines.delay(2500); userScrolling = false }
    }
    LaunchedEffect(active, selecting) {
        if (!userScrolling && !selecting) listState.animateScrollToItem(active, scrollOffset = -120)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().edgeFade(),
        contentPadding = PaddingValues(top = 40.dp, bottom = 240.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            val chosen = selection?.contains(i) == true
            val target = when {
                selecting -> if (chosen) 1f else 0.38f
                i == active -> 1f
                else -> 0.32f
            }
            val alpha by animateFloatAsState(target, tween(300), label = "lineAlpha")
            val lineScale by animateFloatAsState(if (!selecting && i == active) 1f else 0.97f, tween(300), label = "lineScale")
            LyricText(
                text = line.text.ifBlank { "♪" },
                chosen = chosen,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 35.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = lineScale; scaleY = lineScale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .lyricGestures(i, selecting, onTap = { PlayerManager.seekTo(line.timeMs) }, onLongPress = onLongPress, onToggle = onToggle),
            )
        }
    }
}

@Composable
private fun PlainLyrics(
    lines: List<String>,
    selection: Set<Int>?,
    onLongPress: (Int) -> Unit,
    onToggle: (Int) -> Unit,
) {
    val selecting = selection != null
    LazyColumn(
        modifier = Modifier.fillMaxSize().edgeFade(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 120.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            if (line.isBlank()) {
                Spacer(Modifier.height(18.dp))
            } else {
                val chosen = selection?.contains(i) == true
                LyricText(
                    text = line,
                    chosen = chosen,
                    style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 34.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = if (!selecting || chosen) 0.92f else 0.4f }
                        .lyricGestures(i, selecting, onTap = {}, onLongPress = onLongPress, onToggle = onToggle),
                    vertical = 4.dp,
                )
            }
        }
    }
}

@Composable
private fun LyricText(
    text: String,
    chosen: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier,
    vertical: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val fill by animateColorAsState(if (chosen) Color.White.copy(alpha = 0.16f) else Color.Transparent, tween(180), label = "lyricPick")
    Text(
        text = text,
        color = Color.White,
        style = style,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .padding(horizontal = if (chosen) 10.dp else 0.dp, vertical = vertical),
    )
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun QueuePanel() {
    val shuffle by PlayerManager.shuffle.collectAsState()
    val repeatMode by PlayerManager.repeatMode.collectAsState()
    val upNext by PlayerManager.upNext.collectAsState()
    val settings by SettingsRepository.settings.collectAsState()
    val autoplayOn = settings.autoplay
    val queued = remember(upNext) { upNext.filterNot { it.autoplay } }
    val similar = remember(upNext) { upNext.filter { it.autoplay } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QueuePill(Icons.Default.Shuffle, stringResource(R.string.shuffle), shuffle, Modifier.weight(1f)) { PlayerManager.toggleShuffle() }
            QueuePill(
                if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                stringResource(R.string.repeat),
                repeatMode != Player.REPEAT_MODE_OFF,
                Modifier.weight(1f),
            ) { PlayerManager.cycleRepeat() }
            QueuePill(Icons.Default.AllInclusive, stringResource(R.string.autoplay), autoplayOn, Modifier.weight(1f)) {
                SettingsRepository.update { it.copy(autoplay = !it.autoplay) }
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.fillMaxSize().edgeFade(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "next_header") {
                QueueSectionHeader(
                    title = stringResource(R.string.playing_next),
                    action = if (queued.isNotEmpty()) stringResource(R.string.queue_clear) else null,
                    onAction = { PlayerManager.clearUpNext() },
                )
            }
            if (queued.isEmpty()) {
                item(key = "next_empty") {
                    Text(
                        stringResource(R.string.queue_empty),
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    )
                }
            }
            items(queued, key = { "q_${it.index}_${it.item.id}" }) { entry -> QueueRow(entry, dimmed = false) }
            if (autoplayOn) {
                item(key = "auto_header") {
                    Spacer(Modifier.height(14.dp))
                    QueueSectionHeader(title = stringResource(R.string.autoplay), subtitle = stringResource(R.string.autoplay_desc))
                }
                if (similar.isEmpty()) {
                    item(key = "auto_empty") {
                        Text(
                            stringResource(R.string.autoplay_pending),
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                items(similar, key = { "a_${it.index}_${it.item.id}" }) { entry -> QueueRow(entry, dimmed = true) }
            }
        }
    }
}

@Composable
private fun QueueSectionHeader(title: String, subtitle: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (action != null) {
            Box(
                Modifier.heightIn(min = 44.dp).glassClickable(pressedScale = 0.94f, onClick = onAction).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun QueueRow(entry: QueueEntry, dimmed: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.62f else 1f)
            .glassClickable(pressedScale = 0.985f) { PlayerManager.playQueueEntry(entry) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(entry.item.thumbnail, Modifier.size(46.dp), shape = GlassShapes.Tiny, contentDescription = entry.item.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.item.title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.item.artist.isNotBlank()) {
                Text(entry.item.artist, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier.size(48.dp).glassClickable(pressedScale = 0.85f) { PlayerManager.removeQueueEntry(entry) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.remove), tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun QueuePill(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (active) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f), tween(180), label = "pillFill")
    val content by animateColorAsState(if (active) Color.Black else Color.White, tween(180), label = "pillContent")
    Box(
        modifier
            .height(40.dp)
            .semantics {
                contentDescription = label
                toggleableState = androidx.compose.ui.state.ToggleableState(active)
            }
            .glassClickable(pressedScale = 0.95f, onClick = onClick)
            .liquidFill(shape = GlassShapes.Pill, fill = fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TransportRow() {
    val isPlaying by PlayerManager.isPlaying.collectAsState()
    val isBuffering by PlayerManager.isBuffering.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(stringResource(R.string.previous), 72.dp, { PlayerManager.previous() }) {
            Icon(Icons.Default.FastRewind, null, tint = Color.White, modifier = Modifier.size(42.dp))
        }
        GlyphButton(stringResource(if (isPlaying) R.string.pause else R.string.play), 88.dp, { PlayerManager.togglePlayPause() }) {
            Crossfade(
                targetState = if (isBuffering && !isPlaying) 2 else if (isPlaying) 1 else 0,
                animationSpec = tween(160),
                label = "playPause",
            ) { state ->
                when (state) {
                    2 -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
                    1 -> Icon(Icons.Default.Pause, null, tint = Color.White, modifier = Modifier.size(58.dp))
                    else -> Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(62.dp))
                }
            }
        }
        GlyphButton(stringResource(R.string.next), 72.dp, { PlayerManager.next() }) {
            Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(42.dp))
        }
    }
}

@Composable
private fun GlyphButton(description: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bubble by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 90 else 260), label = "bubble")
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "glyphScale")
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(size)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = bubble; scaleX = 0.7f + 0.3f * bubble; scaleY = 0.7f + 0.3f * bubble }
                .liquidFill(shape = CircleShape, fill = Color.White.copy(alpha = 0.14f)),
        )
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { content() }
    }
}

@Composable
private fun BottomToggle(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (active) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.08f), tween(180), label = "bottomFill")
    val tint by animateColorAsState(if (active) Color.Black.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.75f), tween(180), label = "bottomTint")
    Box(
        Modifier
            .size(44.dp)
            .glassClickable(pressedScale = 0.86f, onClick = onClick)
            .liquidFill(shape = CircleShape, fill = fill)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun VolumeSection(active: Boolean) {
    val context = LocalContext.current
    val volumeLabel = stringResource(R.string.volume)
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    if (active) {
        DisposableEffect(Unit) {
            level = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (!dragging) level = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED)
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    fun set(f: Float) {
        level = f.coerceIn(0f, 1f)
        val idx = (level * max).roundToInt()
        if (idx != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
        }
    }

    val thickness by animateDpAsState(if (dragging) 12.dp else 7.dp, label = "volThickness")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeMute, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
        Box(
            Modifier
                .weight(1f)
                .height(30.dp)
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = volumeLabel }
                .pointerInput(Unit) { detectTapGestures { o -> set(o.x / size.width) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onHorizontalDrag = { change, _ -> change.consume(); set(change.position.x / size.width) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(thickness)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .drawBehind {
                        drawRect(Color.White.copy(alpha = if (dragging) 1f else 0.85f), size = Size(size.width * level, size.height))
                    },
            )
        }
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
    }
}

internal fun openOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        val shown = runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        if (shown) return
    }
    val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
        .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(panel) }.isSuccess
    if (!opened) {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
private fun SeekSection() {
    val progress by PlayerManager.progress.collectAsState()
    val positionMs by PlayerManager.positionMs.collectAsState()
    val durationMs by PlayerManager.durationMs.collectAsState()

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val smoothProgress = rememberSmoothProgress(progress)
    var heldFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(heldFraction) {
        if (heldFraction != null) {
            withFrameNanos { }
            withFrameNanos { }
            heldFraction = null
        }
    }
    val shownPositionMs = dragFraction?.let { (it * durationMs).roundToLong() } ?: positionMs

    val thickness by animateDpAsState(if (dragFraction != null) 12.dp else 7.dp, label = "seekThickness")
    val trackColor = Color.White

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        heldFraction = f
                        PlayerManager.seekTo((f * durationMs).roundToLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let {
                                heldFraction = it
                                PlayerManager.seekTo((it * durationMs).roundToLong())
                            }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thickness)
                    .clip(CircleShape)
                    .background(trackColor.copy(alpha = 0.22f))
                    .drawBehind {
                        val dragging = dragFraction
                        val fraction = (dragging ?: heldFraction ?: smoothProgress.value).coerceIn(0f, 1f)
                        drawRect(
                            color = trackColor.copy(alpha = if (dragging != null) 1f else 0.85f),
                            size = Size(size.width * fraction, size.height),
                        )
                    },
            )
        }
        Box(Modifier.fillMaxWidth()) {
            Text(
                formatTime(shownPositionMs),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            SleepBadge(Modifier.align(Alignment.Center))
            Text(
                "-" + formatTime((durationMs - shownPositionMs).coerceAtLeast(0L)),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SleepTimerMenu(expanded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mode by SleepTimer.mode.collectAsState()
    val setMsg = stringResource(R.string.sleep_timer_set)
    val offMsg = stringResource(R.string.sleep_timer_off_toast)
    val minutesLabels = SleepTimer.PRESETS.map { it to pluralStringResource(R.plurals.minutes, it, it) }
    val actions = buildList {
        if (mode != null) add(MenuAction(stringResource(R.string.sleep_timer_off), Icons.Default.Close, accent = true) {
            SleepTimer.cancel()
            Toast.makeText(context, offMsg, Toast.LENGTH_SHORT).show()
        })
        add(MenuAction(stringResource(R.string.sleep_end_of_song), Icons.Default.MusicNote, accent = mode == SleepTimer.Mode.EndOfTrack) {
            SleepTimer.endOfTrack()
            Toast.makeText(context, setMsg, Toast.LENGTH_SHORT).show()
        })
        minutesLabels.forEach { (m, label) ->
            add(MenuAction(label, Icons.Default.Bedtime, accent = (mode as? SleepTimer.Mode.At)?.minutes == m) {
                SleepTimer.start(m)
                Toast.makeText(context, setMsg, Toast.LENGTH_SHORT).show()
            })
        }
    }
    GlassMenu(expanded = expanded, onDismiss = onDismiss, actions = actions)
}

@Composable
private fun SleepBadge(modifier: Modifier = Modifier) {
    val mode by SleepTimer.mode.collectAsState()
    val current = mode ?: return
    val left by produceState(SleepTimer.remainingMs() ?: 0L, current) {
        while (true) {
            value = SleepTimer.remainingMs() ?: 0L
            kotlinx.coroutines.delay(1000L)
        }
    }
    Row(
        modifier
            .liquidFill(shape = GlassShapes.Pill, fill = Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Bedtime, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (current is SleepTimer.Mode.At) formatTime(left) else stringResource(R.string.sleep_end_of_song),
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
