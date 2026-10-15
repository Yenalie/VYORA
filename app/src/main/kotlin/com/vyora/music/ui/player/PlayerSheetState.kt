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

@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    val progress = Animatable(0f)

    val isVisible: Boolean by derivedStateOf { progress.value > 0.001f }

    val isExpanded: Boolean by derivedStateOf { progress.value > 0.5f }

    init {
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

    fun dragBy(delta: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.snapTo((progress.value + delta).coerceIn(0f, 1f))
        }
    }

    fun settle(velocityY: Float, heightPx: Int, flingThresholdPx: Float = 1200f, fromCollapsed: Boolean = false) {
        val p = progress.value
        val open = if (fromCollapsed) {
            velocityY < -flingThresholdPx || (p > 0.25f && velocityY < flingThresholdPx)
        } else {
            !(velocityY > flingThresholdPx || (p < 0.7f && velocityY > -flingThresholdPx))
        }
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
