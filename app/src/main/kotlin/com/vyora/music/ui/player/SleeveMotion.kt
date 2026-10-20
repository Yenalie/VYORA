package com.vyora.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The slow "living cover" drift on the full-bleed sleeve, in the spirit of Apple Music's motion artwork: the
 * picture breathes in and out and wanders a few percent across the frame on a long loop, so the screen never
 * sits dead still while a song plays. It eases to rest when playback pauses and picks up where it left off.
 */
@Stable
internal class SleeveMotion {
    internal val phase = Animatable(0f)
    internal val amount = Animatable(0f)

    fun applyTo(layer: GraphicsLayerScope) {
        val a = amount.value
        if (a <= 0.0005f) return
        val t = phase.value * 2f * PI.toFloat()
        val zoom = 1f + a * (0.07f + 0.025f * sin(t * 2f))
        layer.scaleX = zoom
        layer.scaleY = zoom
        layer.translationX = a * layer.size.width * 0.022f * sin(t)
        layer.translationY = a * layer.size.height * 0.018f * cos(t)
    }
}

private const val SLEEVE_LOOP_MS = 26_000

@Composable
internal fun rememberSleeveMotion(animate: Boolean): SleeveMotion {
    val motion = remember { SleeveMotion() }
    LaunchedEffect(animate) {
        motion.amount.animateTo(if (animate) 1f else 0f, tween(if (animate) 1400 else 900, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            val from = motion.phase.value % 1f
            motion.phase.snapTo(from)
            motion.phase.animateTo(from + 1f, tween(SLEEVE_LOOP_MS, easing = LinearEasing))
        }
    }
    return motion
}

/** Where the zooming player sits for one frame: uniform scale, offset and the rounded window it is cut to. */
internal class ZoomWindow(val scale: Float, val translation: Offset, val shape: Shape)

/** How much of the opening the player takes to fade up over the capsule. */
internal const val ZOOM_FADE_IN = 0.22f

/**
 * iOS 26 Music's zoom: rather than a lone square cover flying out of the mini player, the whole player grows out of
 * the capsule (and shrinks back into it on the way down). At progress 0 the player is scaled to the capsule's
 * width and cut to a capsule-shaped window around the middle of the sleeve; at 1 it is the full screen.
 * Null when there is nothing to zoom from, so the caller falls back to the plain slide.
 */
internal fun ArtworkFlight.zoomWindow(progress: Float, width: Float, height: Float, sheetCornerPx: Float): ZoomWindow? {
    if (!zoom || width < 1f || height < 1f) return null
    val container = sheetContainer?.takeIf { it.isAttached }
    val origin = container?.positionInRoot() ?: Offset.Zero
    val m = mini.translate(-origin)
    if (m.width < 1f || m.height < 1f) return null
    val p = progress.coerceIn(0f, 1f)
    // Fully closed: park the player off screen as usual so its (invisible) controls can't catch taps meant for
    // the mini player underneath. Fully open: no transform at all.
    if (p <= 0.001f || p >= 0.999f) return null
    val t = 1f - (1f - p) * (1f - p)
    val scale = lerpF(m.width / width, 1f, t)
    val windowH = lerpF(m.height, height, t) / scale
    // Start on a strip through the sleeve's centre (a square at the top of the player) rather than its top edge.
    val startTop = (width / 2f - (m.height / (m.width / width)) / 2f).coerceAtLeast(0f)
    val clipTop = startTop * (1f - t)
    val corner = lerpF(m.height / 2f, sheetCornerPx * (1f - p), t) / scale
    val translation = Offset(
        lerpF(m.left, 0f, t),
        lerpF(m.top, 0f, t) - clipTop * scale,
    )
    return ZoomWindow(scale, translation, WindowShape(clipTop, windowH, corner))
}

private class WindowShape(val top: Float, val height: Float, val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bottom = (top + height).coerceAtMost(size.height)
        val r = corner.coerceIn(0f, minOf(size.width, bottom - top) / 2f).let { if (it.isFinite()) it else 0f }
        return Outline.Rounded(RoundRect(0f, top, size.width, bottom, CornerRadius(r)))
    }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
