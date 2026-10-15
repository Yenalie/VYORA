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

/**
 * iOS 26 "matched geometry" between the mini player and Now Playing: while the sheet moves,
 * one artwork flies from the mini player's thumbnail to the big cover (and back), and the
 * two real artworks hide. Everything is read in draw/layer blocks, so a drag never
 * recomposes anything.
 *
 * Coordinates are root coordinates. The big cover's spot is measured relative to the
 * sheet's moving box, then offset by the sheet's fixed container: that is where it will be
 * once the sheet is fully open, regardless of where the sheet is right now.
 */
@Stable
class ArtworkFlight {
    /** Mini player thumbnail, as currently on screen. */
    var mini by mutableStateOf(Rect.Zero)
    var miniCornerPx by mutableFloatStateOf(0f)

    /** Big cover at rest (sheet open). Zero while Now Playing shows Lyrics / Queue. */
    var full by mutableStateOf(Rect.Zero)
    var fullCornerPx by mutableFloatStateOf(0f)

    internal var sheetContainer: LayoutCoordinates? = null
    internal var sheetContent: LayoutCoordinates? = null

    val ready: Boolean get() = mini != Rect.Zero && full != Rect.Zero

    /** True when the flying copy should be drawn instead of the real artworks. */
    fun inFlight(progress: Float) = ready && progress > 0.001f && progress < 0.999f
}

val LocalArtworkFlight = staticCompositionLocalOf<ArtworkFlight?> { null }

/** On the mini player thumbnail: reports where it is and hides it during the flight. */
@Composable
fun Modifier.miniFlightSource(sheet: PlayerSheetState?, corner: Dp): Modifier {
    val flight = LocalArtworkFlight.current ?: return this
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    return this
        .onGloballyPositioned { flight.mini = it.boundsInRoot(); flight.miniCornerPx = cornerPx }
        .graphicsLayer { alpha = if (sheet != null && flight.inFlight(sheet.progress.value)) 0f else 1f }
}

/** On the Now Playing cover. [enabled] = false (Lyrics / Queue mode) disables the flight. */
@Composable
fun Modifier.fullFlightTarget(sheet: PlayerSheetState, corner: Dp, enabled: Boolean): Modifier {
    val flight = LocalArtworkFlight.current ?: return this
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    if (!enabled) {
        androidx.compose.runtime.SideEffect { flight.full = Rect.Zero }
        return this
    }
    return this
        .onGloballyPositioned { art ->
            val content = flight.sheetContent ?: return@onGloballyPositioned
            val container = flight.sheetContainer ?: return@onGloballyPositioned
            if (!content.isAttached || !container.isAttached) return@onGloballyPositioned
            flight.full = content.localBoundingBoxOf(art, clipBounds = false).translate(container.positionInRoot())
            flight.fullCornerPx = cornerPx
        }
        .graphicsLayer { alpha = if (flight.inFlight(sheet.progress.value)) 0f else 1f }
}

internal fun Modifier.sheetContainer(flight: ArtworkFlight?) =
    if (flight == null) this else onGloballyPositioned { flight.sheetContainer = it }

internal fun Modifier.sheetContent(flight: ArtworkFlight?) =
    if (flight == null) this else onGloballyPositioned { flight.sheetContent = it }

/**
 * The flying copy. Laid out once at the big cover's size and moved with a layer transform
 * (scale + translate), so each frame is just a matrix change. Its path leads the sheet a
 * little (eased), the way the iOS cover arrives slightly before the sheet settles.
 */
@Composable
fun ArtworkFlightOverlay(sheet: PlayerSheetState, flight: ArtworkFlight) {
    val thumbnail by PlayerManager.currentThumbnail.collectAsState()
    val full = flight.full
    if (full == Rect.Zero || thumbnail == null) return
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
                // Ease-out on the way up: the cover gets there a touch before the sheet.
                val t = 1f - (1f - p) * (1f - p)
                val from = flight.mini
                val to = flight.full
                val w = from.width + (to.width - from.width) * t
                val s = w / to.width
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
                // Corner radius in layer space so it looks right after scaling.
                val corner = flight.miniCornerPx + (flight.fullCornerPx - flight.miniCornerPx) * t
                shape = RoundedCornerShape(corner / s.coerceAtLeast(0.01f))
                clip = true
                shadowElevation = 24.dp.toPx() * t
            },
    ) {
        Artwork(url = sharp, placeholderKey = thumbnail, shape = androidx.compose.ui.graphics.RectangleShape, modifier = Modifier.size(with(density) { full.width.toDp() }))
    }
}

/** The player sheet, for components that need its progress in draw (mini player). */
val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetState?> { null }
