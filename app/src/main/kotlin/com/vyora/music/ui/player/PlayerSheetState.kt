package com.vyora.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Open/closed state of the full player, as one number: 0f = collapsed (hidden below the
 * screen, only the mini player in the tab bar shows), 1f = fully expanded.
 *
 * [progress] is an [Animatable], so readers that only need it for drawing (graphicsLayer)
 * do not recompose every frame. [isVisible] / [isExpanded] are derived and only change at
 * the edges, which is what composition-time readers should use.
 */
@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    val progress = Animatable(0f)

    /** True while any part of the sheet is on screen (including mid-animation). */
    val isVisible: Boolean by derivedStateOf { progress.value > 0.001f }

    /** True once the sheet is mostly open; used for Back handling. */
    val isExpanded: Boolean by derivedStateOf { progress.value > 0.5f }

    init {
        // A springy open can overshoot 1f by a hair, which would slide the sheet a pixel too
        // far and expose a sliver at the bottom. Bounds make the animation stop at the edge.
        progress.updateBounds(0f, 1f)
    }

    private val openSpec = spring<Float>(dampingRatio = 0.88f, stiffness = 420f)
    private val closeSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 480f)

    fun expand() {
        scope.launch { progress.animateTo(1f, openSpec) }
    }

    fun collapse() {
        scope.launch { progress.animateTo(0f, closeSpec) }
    }

    /** Finger-follows-drag: [delta] is a fraction of the sheet height (positive = opening). */
    fun dragBy(delta: Float) {
        // UNDISPATCHED: apply the snap right now instead of queueing it behind the frame loop,
        // so the sheet tracks the finger with no extra frame of lag.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.snapTo((progress.value + delta).coerceIn(0f, 1f))
        }
    }

    /**
     * Called when the finger lifts. [velocityY] is px/s, positive = moving down.
     * [flingThresholdPx] is the speed (px/s) that counts as a deliberate flick, so the feel
     * is the same on every screen density.
     *
     * - Dragging the open sheet down ([fromCollapsed] = false): it closes on a downward flick
     *   or once it has been pulled past ~30% of the screen, like iOS Now Playing.
     * - Pulling the mini player up ([fromCollapsed] = true): it opens on an upward flick or
     *   once dragged past ~25%, otherwise it drops back into the bar.
     *
     * The release velocity is handed to the spring so the sheet keeps moving the way the
     * finger was, instead of stopping dead and then starting a fresh animation.
     */
    fun settle(velocityY: Float, heightPx: Int, flingThresholdPx: Float = 1200f, fromCollapsed: Boolean = false) {
        val p = progress.value
        val open = if (fromCollapsed) {
            velocityY < -flingThresholdPx || (p > 0.25f && velocityY < flingThresholdPx)
        } else {
            !(velocityY > flingThresholdPx || (p < 0.7f && velocityY > -flingThresholdPx))
        }
        // Sheet progress per second; positive = opening (finger moving up).
        val velocity = -velocityY / heightPx.coerceAtLeast(1)
        scope.launch {
            progress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = if (open) openSpec else closeSpec,
                initialVelocity = velocity,
            )
        }
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerSheetState(scope) }
}
