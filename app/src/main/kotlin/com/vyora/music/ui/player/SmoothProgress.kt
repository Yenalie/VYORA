package com.vyora.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlin.math.abs

@Composable
fun rememberSmoothProgress(target: Float): State<Float> {
    val animatable = remember { Animatable(target) }
    LaunchedEffect(target) {
        val delta = target - animatable.value
        if (delta < 0f || abs(delta) > 0.04f) {
            animatable.snapTo(target)
        } else {
            animatable.animateTo(target, tween(durationMillis = 550, easing = LinearEasing))
        }
    }
    return animatable.asState()
}
