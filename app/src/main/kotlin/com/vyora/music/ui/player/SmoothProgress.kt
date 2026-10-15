package com.vyora.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlin.math.abs

/**
 * PlayerManager publishes progress every 500 ms, so drawing it directly makes the bar move in
 * visible steps. This glides linearly from one sample to the next instead, and snaps (no
 * glide) when the jump is not normal playback: a seek, a new track, or going backwards.
 *
 * Returns a State so callers can read it in the draw phase (drawBehind / drawWithContent)
 * and avoid recomposing every frame.
 */
@Composable
fun rememberSmoothProgress(target: Float): State<Float> {
    val animatable = remember { Animatable(target) }
    LaunchedEffect(target) {
        val delta = target - animatable.value
        if (delta < 0f || abs(delta) > 0.04f) {
            animatable.snapTo(target)
        } else {
            // A touch longer than the 500 ms poll so consecutive glides overlap seamlessly.
            animatable.animateTo(target, tween(durationMillis = 550, easing = LinearEasing))
        }
    }
    return animatable.asState()
}
