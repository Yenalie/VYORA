package com.vyora.music.ui.design

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.ImageRequest
import com.vyora.music.ui.theme.VyoraBackground
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The app-wide ambient light behind every screen.
 *
 * Liquid glass only looks like glass when there is colour behind it to bend and blur;
 * over a flat near-black page the nav bar and mini player read as grey blobs. This paints
 * the current song's colour that the floating glass samples and refracts.
 *
 * It is static (no infinite animation), so it never forces the glass backdrop to
 * re-record on its own; only an artwork change animates it, once, over ~1s.
 */
@Composable
fun AmbientBackground(
    artworkTint: Color?,
    modifier: Modifier = Modifier,
) {
    // The current song's colour washes down from the top of every page (Apple Music's
    // tinted header) with a softer pool at the bottom, where the floating glass bar and
    // mini player sit. Off, or with nothing playing, the page is the plain background.
    val base = artworkTint ?: Color.Transparent
    // The sampled tint is kept dark for text; the glow needs a brighter take on the hue.
    val glowTarget = if (artworkTint == null) Color.Transparent else glowColor(base)
    val glow by animateColorAsState(glowTarget, tween(900), label = "ambientGlow")
    val background = VyoraBackground
    val dark = com.vyora.music.ui.theme.LocalVyoraColors.current.isDark
    // White pages need less: the same alpha reads stronger on light than on black.
    val k = if (dark) 1f else 0.7f
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(background)
                if (glow.alpha == 0f) return@drawBehind
                val w = size.width
                val h = size.height
                drawRect(
                    Brush.verticalGradient(
                        0f to glow.copy(alpha = glow.alpha * 0.55f * k),
                        0.22f to glow.copy(alpha = glow.alpha * 0.30f * k),
                        0.55f to glow.copy(alpha = glow.alpha * 0.08f * k),
                        1f to Color.Transparent,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = glow.alpha * 0.35f * k), Color.Transparent),
                        center = Offset(w * 0.5f, 0f),
                        radius = max(w, h) * 0.6f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = glow.alpha * 0.22f * k), Color.Transparent),
                        center = Offset(w * 0.5f, h * 1.05f),
                        radius = max(w, h) * 0.45f,
                    )
                )
            }
    )
}

/** Same hue as [tint], lifted to a clearly coloured but not neon glow. */
internal fun glowColor(tint: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (tint.red * 255).toInt(), (tint.green * 255).toInt(), (tint.blue * 255).toInt()), hsv)
    // Grey covers stay grey; colourful ones keep their colour.
    hsv[1] = hsv[1].coerceAtMost(0.85f)
    hsv[2] = hsv[2].coerceIn(0.72f, 0.9f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * A dark, vivid average colour of the artwork at [url], for tinting backgrounds.
 * Plain Coil + a few lines of math, so no Palette dependency is needed.
 */
@Composable
fun rememberArtworkTint(url: String?): State<Color> {
    val context = LocalContext.current
    return produceState(initialValue = ArtworkFallbackTint, key1 = url) {
        if (url.isNullOrBlank()) {
            value = ArtworkFallbackTint
            return@produceState
        }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(48)
            .allowHardware(false) // pixels are read, so no hardware bitmap
            .build()
        val bitmap = (Coil.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
        value = bitmap?.let { withContext(Dispatchers.Default) { dominantTint(it) } } ?: ArtworkFallbackTint
    }
}

/** Neutral tint used while (or instead of) reading the artwork colour. */
val ArtworkFallbackTint get() = com.vyora.music.ui.theme.ControlColors.AmbientFallback

/**
 * Colour of the music video playing as Now Playing artwork, sampled from its frames a
 * little more than once a second. Backgrounds prefer it over the cover's colour, so the
 * ambient light follows the picture. Null when no video is showing.
 */
object VideoAmbience {
    private val _tint = MutableStateFlow<Color?>(null)
    val tint: StateFlow<Color?> = _tint.asStateFlow()

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    /** A tiny copy of the latest frame, drawn blurred as the player's living background. */
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** Called off the main thread with a small frame grab. */
    fun publish(frame: Bitmap) {
        _tint.value = dominantTint(frame)
        _frame.value = frame.asImageBitmap()
    }

    fun clear() {
        _tint.value = null
        _frame.value = null
    }
}

private fun dominantTint(bitmap: Bitmap): Color {
    val w = bitmap.width
    val h = bitmap.height
    if (w == 0 || h == 0) return ArtworkFallbackTint
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val hsv = FloatArray(3)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    for (p in pixels) {
        if (android.graphics.Color.alpha(p) < 200) continue
        android.graphics.Color.colorToHSV(p, hsv)
        // Favour vivid, reasonably bright pixels so a mostly-black cover with a bright
        // subject tints to the subject instead of to grey.
        val weight = 0.1 + hsv[1] * hsv[2]
        r += android.graphics.Color.red(p) * weight
        g += android.graphics.Color.green(p) * weight
        b += android.graphics.Color.blue(p) * weight
        total += weight
    }
    if (total <= 0.0) return ArtworkFallbackTint

    val avg = android.graphics.Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
    android.graphics.Color.colorToHSV(avg, hsv)
    hsv[1] = hsv[1].coerceAtMost(0.8f)
    hsv[2] = hsv[2].coerceIn(0.28f, 0.55f) // dark enough for white text, bright enough to read as colour
    return Color(android.graphics.Color.HSVToColor(hsv))
}
