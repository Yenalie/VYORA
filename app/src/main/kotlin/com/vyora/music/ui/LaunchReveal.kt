package com.vyora.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app-opening transition. The splash icon zooms through the screen (MainActivity) while the UI
 * rises into place behind it — slightly small, transparent and low, settling with a soft spring — the
 * way iOS opens Apple Music from its icon.
 */
object LaunchReveal {
    private val splashExiting = MutableStateFlow(false)

    /** Called from the splash exit animation; the UI starts settling in at the same moment. */
    fun onSplashExit() { splashExiting.value = true }

    internal suspend fun awaitSplashExit() {
        // If the platform skips the exit callback (some launchers, tests), don't keep the UI hidden.
        withTimeoutOrNull(FALLBACK_MS) { splashExiting.first { it } }
    }

    const val START_SCALE = 0.92f
    private const val FALLBACK_MS = 1_500L
}

@Composable
fun LaunchRevealBox(animate: Boolean, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        LaunchReveal.awaitSplashExit()
        progress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 210f))
    }
    val rise = with(LocalDensity.current) { 36.dp.toPx() }
    val p = progress.value
    // Only pay for a layer while the transition runs.
    val reveal = if (p != 1f) {
        Modifier.graphicsLayer {
            val scale = LaunchReveal.START_SCALE + (1f - LaunchReveal.START_SCALE) * p
            scaleX = scale
            scaleY = scale
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * rise
        }
    } else Modifier
    Box(Modifier.fillMaxSize().then(reveal)) { content() }
}
