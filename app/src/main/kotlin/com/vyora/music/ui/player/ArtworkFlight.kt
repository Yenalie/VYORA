package com.vyora.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.playback.PlayerManager
import com.vyora.music.ui.design.Artwork

@Stable
class ArtworkFlight {
    var mini by mutableStateOf(Rect.Zero)
    var miniCornerPx by mutableFloatStateOf(0f)

    var full by mutableStateOf(Rect.Zero)
    var fullCornerPx by mutableFloatStateOf(0f)
    /** Lower fraction of the target that dissolves into the background (the full-bleed sleeve); 0 for the card. */
    var fullFade by mutableFloatStateOf(0f)

    /**
     * True while the full-bleed sleeve is up: the whole player zooms out of (and back into) the mini player's
     * capsule instead of a lone square cover flying between the two, the way iOS 26 Music does it.
     */
    var zoom by mutableStateOf(false)

    internal var sheetContainer: LayoutCoordinates? = null
    internal var sheetContent: LayoutCoordinates? = null

    // A collapsed target (e.g. the artwork squeezed to nothing in a short landscape window) would divide by zero
    // below and hand NaN corners to the layer outline, which throws.
    val ready: Boolean get() = !zoom && mini.width >= 1f && mini.height >= 1f && full.width >= 1f && full.height >= 1f

    fun inFlight(progress: Float) = ready && progress > 0.001f && progress < 0.999f
}

val LocalArtworkFlight = staticCompositionLocalOf<ArtworkFlight?> { null }

@Composable
fun Modifier.miniFlightSource(sheet: PlayerSheetState?, corner: Dp): Modifier {
    val flight = LocalArtworkFlight.current ?: return this
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    return this
        .onGloballyPositioned { flight.mini = it.boundsInRoot(); flight.miniCornerPx = cornerPx }
        .graphicsLayer { alpha = if (sheet != null && flight.inFlight(sheet.progress.value)) 0f else 1f }
}

@Composable
fun Modifier.fullFlightTarget(sheet: PlayerSheetState, corner: Dp, enabled: Boolean, fade: Float = 0f): Modifier {
    val flight = LocalArtworkFlight.current ?: return this
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    if (!enabled) return this
    return this
        .onGloballyPositioned { art ->
            val content = flight.sheetContent ?: return@onGloballyPositioned
            val container = flight.sheetContainer ?: return@onGloballyPositioned
            if (!content.isAttached || !container.isAttached) return@onGloballyPositioned
            flight.full = content.localBoundingBoxOf(art, clipBounds = false).translate(container.positionInRoot())
            flight.fullCornerPx = cornerPx
            flight.fullFade = fade
        }
        .graphicsLayer { alpha = if (flight.inFlight(sheet.progress.value)) 0f else 1f }
}

internal fun Modifier.sheetContainer(flight: ArtworkFlight?) =
    if (flight == null) this else onGloballyPositioned { flight.sheetContainer = it }

internal fun Modifier.sheetContent(flight: ArtworkFlight?) =
    if (flight == null) this else onGloballyPositioned { flight.sheetContent = it }

@Composable
fun ArtworkFlightOverlay(sheet: PlayerSheetState, flight: ArtworkFlight) {
    val thumbnail by PlayerManager.currentThumbnail.collectAsState()
    val full = flight.full
    if (full.width < 1f || full.height < 1f || thumbnail == null || flight.zoom) return
    val density = LocalDensity.current
    val sharp = remember(thumbnail) { YouTubeClient.artUrl(thumbnail, 1080) }
    Box(
        Modifier
            .size(with(density) { full.width.toDp() }, with(density) { full.height.toDp() })
            .graphicsLayer {
                val p = sheet.progress.value
                if (!flight.inFlight(p)) {
                    alpha = 0f
                    return@graphicsLayer
                }
                val t = flightT(p)
                val from = flight.mini
                val to = flight.full
                val w = from.width + (to.width - from.width) * t
                val s = (w / to.width).takeIf { it.isFinite() && it > 0f } ?: 1f
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = s
                scaleY = s
                val pos = Offset(
                    from.left + (to.left - from.left) * t,
                    from.top + (to.top - from.top) * t,
                )
                translationX = pos.x
                translationY = pos.y
                alpha = 1f
                val corner = flight.miniCornerPx + (flight.fullCornerPx - flight.miniCornerPx) * t
                shape = RoundedCornerShape(corner / s.coerceAtLeast(0.01f))
                clip = true
                // The full-bleed sleeve has no card edge to cast a shadow from.
                shadowElevation = 24.dp.toPx() * t * (1f - flight.fullFade / HERO_FADE_FRACTION).coerceIn(0f, 1f)
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Lands already dissolving into the background when the target is the full-bleed sleeve.
                val fade = flight.fullFade * flightT(sheet.progress.value)
                if (fade > 0.001f) {
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, (1f - fade) to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        Artwork(url = sharp, placeholderKey = thumbnail, shape = androidx.compose.ui.graphics.RectangleShape, modifier = Modifier.size(with(density) { full.width.toDp() }))
        if (flight.fullFade > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(HERO_SCRIM_FRACTION)
                    .graphicsLayer { alpha = flightT(sheet.progress.value) }
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = HERO_SCRIM_ALPHA), Color.Transparent))),
            )
        }
    }
}

/** Eased flight position for sheet progress [p]. */
private fun flightT(p: Float): Float = 1f - (1f - p) * (1f - p)

val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetState?> { null }
