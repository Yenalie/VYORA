package com.vyora.music.ui.design

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vyora.music.ui.glass.GlassStyle
import com.vyora.music.ui.glass.LocalGlassEffectConfig
import com.vyora.music.ui.glass.isGlassAllowed
import com.vyora.music.ui.glass.liquidGlass

@Composable
fun Modifier.liquidFill(
    shape: CornerBasedShape,
    fill: Color,
    edge: Boolean = true,
    rimAlpha: Float = 0.55f,
    overlay: Boolean = false,
): Modifier {
    val base = LocalGlassEffectConfig.current
    if (!base.globalEnabled || !base.contentEnabled || base.style == GlassStyle.TRANSPARENT || !isGlassAllowed()) {
        val flat = if (overlay) fill.copy(alpha = maxOf(fill.alpha, OVERLAY_FALLBACK_ALPHA)) else fill
        return this.glass(shape = shape, fill = flat, sheen = !overlay)
    }
    val tint = fill.copy(alpha = 1f)
    val opacity = fill.alpha.coerceIn(0.04f, 1f)
    val config = remember(base, tint, opacity) {
        base.copy(surfaceTintColor = tint, surfaceOpacity = opacity)
    }
    return this.liquidGlass(
        config = config,
        shape = shape,
        applyEdgeEffects = edge,
        highlightAlpha = (rimAlpha * base.highlightOpacity / 0.7f).coerceIn(0f, 1f),
    )
}

private const val OVERLAY_FALLBACK_ALPHA = 0.97f
