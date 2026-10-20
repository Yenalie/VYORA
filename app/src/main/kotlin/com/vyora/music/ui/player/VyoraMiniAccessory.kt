package com.vyora.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.playback.PlayerManager
import com.vyora.music.ui.components.GlassMenu
import com.vyora.music.ui.components.MenuAction
import com.vyora.music.ui.components.shareSong
import com.vyora.music.ui.glass.floatingtabbar.FloatingTabBarInlineHeight
import com.vyora.music.ui.glass.floatingtabbar.LocalFloatingTabBarCollapseProgress
import com.vyora.music.ui.glass.interaction.InteractiveHighlight
import com.vyora.music.ui.theme.VyoraSurfaceSoft
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun VyoraMiniAccessory(
    isInline: Boolean,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDrag: (dyPx: Float) -> Unit = {},
    onOpenRelease: (velocityY: Float) -> Unit = {},
    /** Edge-to-edge bar docked on the opaque nav bar (Liquid Glass off), like Apple Music for Android. */
    docked: Boolean = false,
) {
    val title by PlayerManager.currentTitle.collectAsState()
    val artist by PlayerManager.currentArtist.collectAsState()
    val thumbnail by PlayerManager.currentThumbnail.collectAsState()
    val isPlaying by PlayerManager.isPlaying.collectAsState()
    val isBuffering by PlayerManager.isBuffering.collectAsState()
    val currentItem by PlayerManager.currentItem.collectAsState()
    val liked by LibraryManager.likedSongs.collectAsState()
    val showSpinner by produceState(false, isBuffering) {
        if (isBuffering) { delay(350); value = true } else value = false
    }
    var menuOpen by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }

    val layoutDirection = LocalLayoutDirection.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestOpenDrag by rememberUpdatedState(onOpenDrag)
    val latestOpenRelease by rememberUpdatedState(onOpenRelease)

    var widthPx by remember { mutableIntStateOf(1) }
    val offsetX = remember { Animatable(0f) }
    val lean = remember { Animatable(0f) }
    val returnSpring = remember { spring<Float>(dampingRatio = 0.82f, stiffness = 420f) }
    val enterSpring = remember { spring<Float>(dampingRatio = 0.9f, stiffness = 380f) }
    val exitSpring = remember { spring<Float>(dampingRatio = 1f, stiffness = 1100f) }
    val leanSpring = remember { spring<Float>(dampingRatio = 0.5f, stiffness = 520f) }
    val flingPx = with(density) { 650.dp.toPx() }
    val minFlickPx = with(density) { 18.dp.toPx() }
    val edgeFadePx = with(density) { 16.dp.toPx() }

    var skipping by remember { mutableStateOf(false) }

    suspend fun skip(dir: Float, velocity: Float = 0f) {
        skipping = true
        try {
            val before = PlayerManager.currentItem.value?.id
            val restartsTrack = dir > 0 && PlayerManager.positionMs.value > 3_000
            if (restartsTrack) {
                PlayerManager.previous()
                offsetX.animateTo(0f, returnSpring, initialVelocity = velocity)
                return
            }
            offsetX.animateTo(dir * widthPx * 0.62f, exitSpring, initialVelocity = velocity)
            if (dir > 0) PlayerManager.previous() else PlayerManager.next()
            val changed = withTimeoutOrNull(700) {
                PlayerManager.currentItem.first { it?.id != before }
            } != null
            if (changed) offsetX.snapTo(-dir * widthPx * 0.4f)
            offsetX.animateTo(0f, enterSpring)
        } finally {
            skipping = false
        }
    }

    var lastId by remember { mutableStateOf(currentItem?.id) }
    LaunchedEffect(currentItem?.id) {
        val id = currentItem?.id
        if (lastId != null && id != null && id != lastId && !skipping) {
            offsetX.snapTo(widthPx * 0.3f)
            offsetX.animateTo(0f, enterSpring)
        }
        lastId = id
    }

    val upNext by PlayerManager.upNext.collectAsState()
    val nextThumb = upNext.firstOrNull()?.item?.thumbnail
    LaunchedEffect(nextThumb) {
        if (nextThumb != null) context.imageLoader.enqueue(ImageRequest.Builder(context).data(nextThumb).build())
    }

    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !docked) 1.035f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "accessoryPressScale",
    )
    val highlight = remember(scope) { InteractiveHighlight(animationScope = scope) }

    val collapseProgress = LocalFloatingTabBarCollapseProgress.current
    val t = collapseProgress?.invoke() ?: if (isInline) 1f else 0f
    val compact = t >= 0.5f

    val rowMinHeight = if (docked) 64.dp else lerp(ExpandedPillHeight, FloatingTabBarInlineHeight, t)
    val artSize = if (docked) 48.dp else lerp(42.dp, 36.dp, t)
    val artRadius = if (docked) 6.dp else lerp(12.dp, 10.dp, t)
    val edgeInset = if (docked) 8.dp else (rowMinHeight - artSize) / 2
    val controlSize = 40.dp
    val iconSize = lerp(26.dp, 22.dp, t)
    val endInset = (edgeInset - (controlSize - iconSize) / 2).coerceAtLeast(0.dp)
    val extrasAlpha = (1f - t * 2f).coerceIn(0f, 1f)

    val gestures = Modifier.pointerInput(layoutDirection) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            longPressed = false
            val tracker = VelocityTracker().apply { addPointerInputChange(down) }
            var horizontal = false
            val afterSlop = awaitTouchSlopOrCancellation(down.id) { change, over ->
                horizontal = abs(over.x) > abs(over.y) * 0.85f
                change.consume()
            } ?: return@awaitEachGesture
            if (longPressed) return@awaitEachGesture
            if (horizontal && skipping) return@awaitEachGesture
            tracker.addPointerInputChange(afterSlop)

            val limit = widthPx * 0.42f
            val commit = widthPx * 0.2f
            fun resisted(x: Float) = sign(x) * limit * (1f - exp(-abs(x) / limit))
            fun unresisted(v: Float) = -sign(v) * limit * ln(1f - (abs(v) / limit).coerceAtMost(0.98f))
            var raw = unresisted(offsetX.value)
            var armed = abs(offsetX.value) >= commit

            if (horizontal) {
                raw += if (rtl) -afterSlop.positionChange().x else afterSlop.positionChange().x
            } else {
                latestOpenDrag(afterSlop.positionChange().y)
            }
            drag(afterSlop.id) { change ->
                tracker.addPointerInputChange(change)
                val d = change.positionChange()
                if (horizontal) {
                    raw += if (rtl) -d.x else d.x
                    val shown = resisted(raw)
                    val nowArmed = abs(shown) >= commit
                    if (nowArmed != armed) {
                        armed = nowArmed
                        haptics.performHapticFeedback(
                            if (armed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentFrequentTick
                        )
                    }
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        offsetX.snapTo(shown)
                        lean.snapTo(shown)
                    }
                } else {
                    latestOpenDrag(d.y)
                }
                change.consume()
            }
            val velocity = tracker.calculateVelocity()
            if (!horizontal) {
                latestOpenRelease(velocity.y)
                return@awaitEachGesture
            }
            val vx = if (rtl) -velocity.x else velocity.x
            val vShown = vx * exp(-abs(raw) / limit)
            val shown = offsetX.value
            val flick = abs(vx) > flingPx && abs(shown) > minFlickPx && sign(vx) == sign(shown)
            scope.launch { lean.animateTo(0f, leanSpring, initialVelocity = vShown) }
            if (armed || flick) {
                val dir = sign(shown).takeIf { it != 0f } ?: sign(vx)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch { skip(dir, if (sign(vShown) == dir) vShown else 0f) }
            } else {
                scope.launch { offsetX.animateTo(0f, returnSpring, initialVelocity = vShown) }
            }
        }
    }

    val openLabel = stringResource(R.string.open_player)
    val moreLabel = stringResource(R.string.more)
    val nextLabel = stringResource(R.string.next)
    val previousLabel = stringResource(R.string.previous)
    val shareTitle = stringResource(R.string.share_song)
    val isLiked = currentItem?.let { item -> liked.any { it.id == item.id } } == true
    val favoriteLabel = stringResource(if (isLiked) R.string.unfavorite else R.string.favorite)

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .graphicsLayer {
                val x = lean.value
                val pull = (abs(x) / (widthPx * 0.2f)).coerceIn(-0.4f, 1f)
                scaleX = pressScale * (1f + 0.025f * pull)
                scaleY = pressScale * (1f - 0.012f * pull)
                translationX = (if (layoutDirection == LayoutDirection.Rtl) -x else x) * 0.06f
            }
            .then(modifier)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .then(highlight.modifier)
            .clipToBounds()
            .then(highlight.gestureModifier)
            .then(gestures),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = pressSource,
                    indication = null,
                    onClickLabel = openLabel,
                    onClick = onClick,
                    onLongClickLabel = moreLabel,
                    onLongClick = {
                        longPressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(nextLabel) { scope.launch { skip(-1f) }; true },
                        CustomAccessibilityAction(previousLabel) { scope.launch { skip(1f) }; true },
                    )
                }
                .heightIn(min = rowMinHeight)
                .padding(start = if (docked) 16.dp else edgeInset, end = if (docked) 8.dp else endInset, top = edgeInset, bottom = edgeInset),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        compositingStrategy = if (offsetX.value != 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    }
                    .drawWithContent {
                        drawContent()
                        val moved = abs(offsetX.value)
                        if (moved > 0.5f && size.width > 0f) {
                            val f = (minOf(edgeFadePx, moved * 0.6f) / size.width).coerceIn(0.001f, 0.25f)
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    f to Color.Black,
                                    1f - f to Color.Black,
                                    1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
            ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val x = offsetX.value
                        translationX = if (layoutDirection == LayoutDirection.Rtl) -x else x
                        alpha = (1f - abs(x) / (widthPx * 0.6f)).coerceIn(0f, 1f)
                    },
            ) {
                val request = remember(thumbnail) {
                    ImageRequest.Builder(context).data(thumbnail).crossfade(160).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .miniFlightSource(LocalPlayerSheet.current, artRadius)
                        .size(artSize)
                        .clip(RoundedCornerShape(artRadius))
                        .background(VyoraSurfaceSoft),
                )

                Spacer(Modifier.width(lerp(12.dp, 10.dp, t)))

                Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = lerp(15.sp, 13.sp, t),
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp,
                        ),
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artist.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = lerp(13.sp, 11.sp, t)),
                        color = contentColor.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            }

            MiniControl(
                description = stringResource(if (isPlaying) R.string.pause else R.string.play),
                size = controlSize,
                onClick = { PlayerManager.togglePlayPause() },
            ) {
                AnimatedContent(
                    targetState = if (showSpinner) null else isPlaying,
                    transitionSpec = {
                        (scaleIn(spring(dampingRatio = 0.55f, stiffness = 700f), initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                            (scaleOut(tween(110), targetScale = 0.6f) + fadeOut(tween(90)))
                    },
                    label = "miniPlayPause",
                ) { playing ->
                    if (playing == null) {
                        CircularProgressIndicator(
                            color = contentColor,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(iconSize - 4.dp),
                        )
                    } else {
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
            }
            if (!compact) {
                Box(Modifier.graphicsLayer { alpha = extrasAlpha }) {
                    MiniControl(
                        description = stringResource(R.string.next),
                        size = controlSize,
                        onClick = { scope.launch { skip(-1f) } },
                    ) {
                        Icon(Icons.Default.FastForward, null, tint = contentColor, modifier = Modifier.size(iconSize))
                    }
                }
            }
        }

        val item = currentItem
        if (item != null) {
            GlassMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                actions = listOf(
                    MenuAction(favoriteLabel, if (isLiked) Icons.Default.Star else Icons.Default.StarBorder, accent = isLiked) {
                        LibraryManager.toggleLike(item)
                    },
                    MenuAction(previousLabel, Icons.Default.FastRewind) { scope.launch { skip(1f) } },
                    MenuAction(shareTitle, Icons.Default.IosShare) { shareSong(context, item, shareTitle) },
                ),
            )
        }
    }
}

private val ExpandedPillHeight = 60.dp

@Composable
private fun MiniControl(
    description: String,
    size: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.82f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "miniControlPress",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = description) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            }
            .androidxSemanticsDescription(description),
    ) { content() }
}

private fun Modifier.androidxSemanticsDescription(text: String) =
    this.then(Modifier.semantics { contentDescription = text })
