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

/**
 * Real liquid glass for in-content controls, using the same engine as the floating tab bar
 * and mini player ([liquidGlass]: vibrancy, blur, lens refraction, specular rim, shadow).
 *
 * It samples whatever `LocalAppBackdrop` is in scope. The app provides a backdrop per area so
 * a surface never samples a layer that contains itself:
 *  - pages in the NavHost sample the ambient background layer,
 *  - the album page samples its own tinted page layer (toolbar: the scrolling page itself),
 *  - the full player samples its living artwork background,
 *  - menus and toasts (drawn above the app) sample the whole app layer.
 *
 * [fill] carries both the glass tint colour and its strength (alpha), so existing call sites
 * keep their meaning: `GlassFillStrong` = a light white frost, accent = tinted red glass.
 * Devices without RenderEffect (or low-RAM), or Liquid Glass switched off, get the flat
 * frosted fallback ([glass]). Surfaces floating over content (menus, toasts) pass [overlay]:
 * without real glass behind them a see-through fill only makes their text harder to read,
 * so the fallback turns nearly opaque.
 */
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

/** Opacity of menus and toasts when they cannot be real glass. */
private const val OVERLAY_FALLBACK_ALPHA = 0.97f
