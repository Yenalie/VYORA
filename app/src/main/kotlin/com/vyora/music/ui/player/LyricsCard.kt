package com.vyora.music.ui.player

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.vyora.music.R
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.liquidFill
import com.vyora.music.ui.theme.LocalVyoraColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Background behind the glass card. The glass always refracts whichever one is chosen. */
enum class LyricsCardStyle { ARTWORK, PHOTO, COLOR, DARK }

object LyricsCardRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val MAX_LINES = 6
    private const val FROST_SCALE = 4
    private val ACCENT = 0xFFFA2D48.toInt()

    suspend fun loadArtwork(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .size(720)
            .build()
        val result = context.imageLoader.execute(request)
        return (result as? SuccessResult)?.drawable?.toBitmap()?.let {
            if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
        }
    }

    /** Decodes a picked photo in software memory, no larger than needed for a 1080 x 1920 card. */
    fun loadPhoto(context: Context, uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            // Enough pixels to cover 1080 x 1920 after a center crop, never more.
            val scale = maxOf(WIDTH / w.toFloat(), HEIGHT / h.toFloat()).coerceAtMost(1f)
            decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
    }.getOrNull()

    fun render(
        art: Bitmap?,
        title: String,
        artist: String,
        lines: List<String>,
        style: LyricsCardStyle,
        photo: Bitmap? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
    ): Bitmap {
        val tint = dominantColor(photo?.takeIf { style == LyricsCardStyle.PHOTO } ?: art)
        val background = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        drawBackground(Canvas(background), art, photo, tint, style)

        // ---- Layout (proportions measured from the reference card) ----
        val marginX = 86f
        val cardWidth = WIDTH - marginX * 2
        val pad = 76f
        val artSize = 148f
        val textWidth = (cardWidth - pad * 2).toInt()

        // Lyrics typography, Apple Music share-card style: semibold rather than black weight, tight leading
        // inside a lyric line that wraps, a clearly larger gap between lyric lines, and balanced breaks so a
        // line never leaves one lonely word behind.
        val lyricLines = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = 0xF2FFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            letterSpacing = -0.012f
            setShadowLayer(20f, 0f, 2f, 0x2E000000)
        }
        val maxLyricHeight = HEIGHT * 0.46f
        var size = 58f
        var blocks: List<StaticLayout>
        var paragraphGap: Float
        while (true) {
            lyricPaint.textSize = size
            paragraphGap = size * 0.42f
            blocks = lyricLines.map { line ->
                StaticLayout.Builder.obtain(line, 0, line.length, lyricPaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.14f)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
                    .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                    .setIncludePad(false)
                    .build()
            }
            val total = blocks.sumOf { it.height } + paragraphGap * (blocks.size - 1).coerceAtLeast(0)
            if (total <= maxLyricHeight || size <= 34f) break
            size -= 2f
        }
        val lyricHeight = (blocks.sumOf { it.height } + paragraphGap * (blocks.size - 1).coerceAtLeast(0)).roundToInt()

        val headerGap = 60f
        val footerGap = 84f
        val footerHeight = 28f
        val cardHeight = (pad + artSize + headerGap + lyricHeight + footerGap + footerHeight + pad)
            .coerceAtLeast(cardWidth * 0.82f)
        val top = ((HEIGHT - cardHeight) / 2f - 20f).coerceAtLeast(110f)
        val card = RectF(marginX, top, marginX + cardWidth, top + cardHeight)
        val radius = 92f

        // ---- Glass ----
        val sharp = IntArray(WIDTH * HEIGHT)
        background.getPixels(sharp, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val fw = WIDTH / FROST_SCALE
        val fh = HEIGHT / FROST_SCALE
        val small = Bitmap.createScaledBitmap(background, fw, fh, true)
        val smallPx = IntArray(fw * fh).also { small.getPixels(it, 0, fw, 0, 0, fw, fh) }
        val frost = LyricsGlass.blur(smallPx, fw, fh, radius = 3)
        // Adapt to the background under the card: bright scenes get dimmed so white text stays legible, very
        // dark ones get a little more frost so the slab still reads as glass.
        val luma = LyricsGlass.meanLuma(
            frost, fw, fh,
            (card.left / FROST_SCALE).toInt(), (card.top / FROST_SCALE).toInt(),
            (card.right / FROST_SCALE).toInt(), (card.bottom / FROST_SCALE).toInt(),
        )
        val params = LyricsGlass.Params(
            radius = radius,
            bevel = 62f,
            refraction = 78f,
            dispersion = 14f,
            tint = if (luma < 0.12f) 0.10f else 0.06f,
            dim = ((luma - 0.5f) * 0.6f).coerceIn(0f, 0.24f),
        )
        val dst = sharp.copyOf()
        LyricsGlass.render(
            dst, sharp, WIDTH, HEIGHT, frost, fw, fh, FROST_SCALE.toFloat(),
            card.left, card.top, card.right, card.bottom, params,
        )
        val out = Bitmap.createBitmap(dst, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        // ---- Header: artwork, title, artist, waveform ----
        val artRect = RectF(card.left + pad, card.top + pad, card.left + pad + artSize, card.top + pad + artSize)
        val artRadius = 26f
        val artShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x01000000; setShadowLayer(18f, 0f, 6f, 0x44000000) }
        canvas.drawRoundRect(artRect, artRadius, artRadius, artShadow)
        if (art != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(artRect, artRadius, artRadius, Path.Direction.CW) })
            canvas.drawBitmap(art, centerSquare(art), artRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
        } else {
            canvas.drawRoundRect(artRect, artRadius, artRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        }
        canvas.drawRoundRect(artRect, artRadius, artRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0x2EFFFFFF
        })

        val waveH = 46f
        val waveRight = card.right - pad
        val waveCenterY = artRect.top + artSize * 0.36f
        val bars = floatArrayOf(0.42f, 0.78f, 1f, 0.66f, 0.38f)
        val barW = 6f
        val barGap = 6.5f
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0FFFFFF.toInt() }
        var bx = waveRight - bars.size * barW - (bars.size - 1) * barGap
        val waveLeft = bx
        bars.forEach { f ->
            val hBar = waveH * f
            canvas.drawRoundRect(RectF(bx, waveCenterY - hBar / 2, bx + barW, waveCenterY + hBar / 2), barW / 2, barW / 2, wavePaint)
            bx += barW + barGap
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = 0xFAFFFFFF.toInt()
            textSize = 39f
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            letterSpacing = -0.008f
            setShadowLayer(12f, 0f, 1f, 0x2A000000)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = 0x9EFFFFFF.toInt()
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        }
        val textLeft = artRect.right + 34f
        val headerTextWidth = waveLeft - 28f - textLeft
        val titleText = TextUtils.ellipsize(title, titlePaint, headerTextWidth, TextUtils.TruncateAt.END).toString()
        val artistText = TextUtils.ellipsize(artist, artistPaint, headerTextWidth, TextUtils.TruncateAt.END).toString()
        val titleBaseline = artRect.top + artSize * 0.36f + 14f
        canvas.drawText(titleText, textLeft, titleBaseline, titlePaint)
        canvas.drawText(artistText, textLeft, titleBaseline + 48f, artistPaint)

        // ---- Lyrics ----
        var lyricTop = artRect.bottom + headerGap
        blocks.forEach { block ->
            canvas.save()
            canvas.translate(card.left + pad, lyricTop)
            block.draw(canvas)
            canvas.restore()
            lyricTop += block.height + paragraphGap
        }

        // ---- Footer: V Y O R A + red dot, progress, time ----
        val footerY = card.bottom - pad - footerHeight / 2f
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3FFFFFF.toInt()
            textSize = 24f
            letterSpacing = 0.62f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val brandText = "VYORA"
        val brandBaseline = footerY + 8.5f
        canvas.drawText(brandText, card.left + pad, brandBaseline, brand)
        // letterSpacing also pads after the last glyph; place the dot one gap after the A.
        val brandW = brand.measureText(brandText)
        canvas.drawCircle(card.left + pad + brandW + 20f, footerY, 6.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })

        if (durationMs > 0L) {
            val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3FFFFFF.toInt()
                textSize = 25f
                typeface = Typeface.DEFAULT
            }
            val time = formatClock(positionMs.coerceIn(0L, durationMs))
            val timeW = timePaint.measureText(time)
            val timeX = card.right - pad - timeW
            canvas.drawText(time, timeX, brandBaseline, timePaint)
            val barRight = timeX - 24f
            val barLeft = barRight - cardWidth * 0.205f
            val track = RectF(barLeft, footerY - 3f, barRight, footerY + 3f)
            canvas.drawRoundRect(track, 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF })
            val frac = (positionMs / durationMs.toFloat()).coerceIn(0f, 1f)
            if (frac > 0f) {
                val fill = RectF(barLeft, track.top, barLeft + (barRight - barLeft) * frac, track.bottom)
                canvas.drawRoundRect(fill, 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
            }
        }
        return out
    }

    internal fun formatClock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun drawBackground(canvas: Canvas, art: Bitmap?, photo: Bitmap?, tint: Int, style: LyricsCardStyle) {
        val full = RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        when {
            style == LyricsCardStyle.PHOTO && photo != null -> {
                // The user's own photo, softened just enough that the glass reads over it.
                drawCover(canvas, photo, full, blurRadius = 2, scale = 4)
                scrim(canvas, full, 0x26000000, 0x59000000)
            }
            style == LyricsCardStyle.ARTWORK && art != null || style == LyricsCardStyle.PHOTO && art != null -> {
                drawCover(canvas, art!!, full, blurRadius = 7, scale = 4)
                scrim(canvas, full, 0x2E000000, 0x73000000)
            }
            style == LyricsCardStyle.DARK -> {
                canvas.drawRect(full, Paint().apply { color = 0xFF0B0B0E.toInt() })
                blobs(canvas, tint, alpha = 0x88)
            }
            else -> {
                val dark = ColorUtils.blendARGB(tint, 0xFF000000.toInt(), 0.6f)
                canvas.drawRect(full, Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, full.bottom, tint, dark, Shader.TileMode.CLAMP)
                })
                blobs(canvas, tint, alpha = 0xC0)
            }
        }
    }

    /** Center-crops [src] to 9:16, blurs it at 1/[scale] resolution and stretches it over [full]. */
    private fun drawCover(canvas: Canvas, src: Bitmap, full: RectF, blurRadius: Int, scale: Int) {
        val target = WIDTH / HEIGHT.toFloat()
        val srcRatio = src.width / src.height.toFloat()
        val crop = if (srcRatio > target) {
            val w = (src.height * target).roundToInt()
            Rect((src.width - w) / 2, 0, (src.width - w) / 2 + w, src.height)
        } else {
            val h = (src.width / target).roundToInt()
            Rect(0, (src.height - h) / 2, src.width, (src.height - h) / 2 + h)
        }
        val sw = WIDTH / scale
        val sh = HEIGHT / scale
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, crop, Rect(0, 0, sw, sh), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(sw * sh).also { small.getPixels(it, 0, sw, 0, 0, sw, sh) }
        val blurred = LyricsGlass.blur(px, sw, sh, blurRadius)
        small.setPixels(blurred, 0, sw, 0, 0, sw, sh)
        canvas.drawBitmap(small, null, full, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
    }

    private fun scrim(canvas: Canvas, full: RectF, topColor: Int, bottomColor: Int) {
        canvas.drawRect(full, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, full.bottom, topColor, bottomColor, Shader.TileMode.CLAMP)
        })
    }

    /** Soft light pools so plain backgrounds still give the glass something to bend. */
    private fun blobs(canvas: Canvas, tint: Int, alpha: Int) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(tint, hsl)
        val spots = listOf(
            Triple(0.18f, 0.22f, 0.55f) to 28f,
            Triple(0.86f, 0.40f, 0.48f) to -34f,
            Triple(0.30f, 0.80f, 0.60f) to 52f,
            Triple(0.92f, 0.92f, 0.42f) to -12f,
        )
        spots.forEach { (spot, hueShift) ->
            val (fx, fy, fr) = spot
            val c = ColorUtils.HSLToColor(floatArrayOf((hsl[0] + hueShift + 360f) % 360f, (hsl[1] * 1.1f).coerceAtMost(0.9f), 0.5f))
            val cx = WIDTH * fx
            val cy = HEIGHT * fy
            val r = WIDTH * fr
            canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                shader = RadialGradient(cx, cy, r, ColorUtils.setAlphaComponent(c, alpha), 0x00000000, Shader.TileMode.CLAMP)
            })
        }
    }

    private fun centerSquare(bitmap: Bitmap): Rect {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    internal fun dominantColor(art: Bitmap?): Int {
        if (art == null) return 0xFF7A2333.toInt()
        val small = Bitmap.createScaledBitmap(art, 24, 24, true)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weight = 0.0
        val hsl = FloatArray(3)
        for (x in 0 until small.width) for (y in 0 until small.height) {
            val c = small.getPixel(x, y)
            ColorUtils.colorToHSL(c, hsl)
            val w = 0.15 + hsl[1] * (1 - kotlin.math.abs(hsl[2] - 0.5f) * 1.6f).coerceAtLeast(0f)
            r += android.graphics.Color.red(c) * w
            g += android.graphics.Color.green(c) * w
            b += android.graphics.Color.blue(c) * w
            weight += w
        }
        val avg = android.graphics.Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt())
        ColorUtils.colorToHSL(avg, hsl)
        hsl[1] = (hsl[1] * 1.25f).coerceAtMost(0.85f)
        hsl[2] = hsl[2].coerceIn(0.22f, 0.42f)
        return ColorUtils.HSLToColor(hsl)
    }

    fun writeShareFile(context: Context, bitmap: Bitmap): android.net.Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "VYORA-lyrics-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun share(context: Context, uri: android.net.Uri, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "VYORA-lyrics-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VYORA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            resolver.delete(uri, null, null)
            false
        }
    }
}


@Composable
fun LyricsCardDialog(
    item: SimpleItem,
    lines: List<String>,
    onDismiss: () -> Unit,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(LyricsCardStyle.ARTWORK) }
    var art by remember { mutableStateOf<Bitmap?>(null) }
    var artLoaded by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var card by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.lyrics_card_share)
    val saved = stringResource(R.string.lyrics_card_saved)
    val saveFailed = stringResource(R.string.lyrics_card_save_failed)
    val photoFailed = stringResource(R.string.lyrics_card_photo_failed)
    val duration = if (durationMs > 0L) durationMs else item.duration * 1000L

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { LyricsCardRenderer.loadPhoto(context, uri) }
            if (picked != null) {
                photo = picked
                style = LyricsCardStyle.PHOTO
            } else {
                android.widget.Toast.makeText(context, photoFailed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun pickPhoto() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    LaunchedEffect(item.id) {
        art = runCatching { LyricsCardRenderer.loadArtwork(context, item.thumbnail) }.getOrNull()
        artLoaded = true
    }
    LaunchedEffect(artLoaded, style, photo) {
        if (!artLoaded) return@LaunchedEffect
        rendering = true
        card = withContext(Dispatchers.Default) {
            LyricsCardRenderer.render(art, item.title, item.artist, lines, style, photo, positionMs, duration)
        }
        rendering = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(30.dp)
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .clip(shape)
                .liquidFill(shape = shape, fill = VyoraGroupedCell.copy(alpha = 0.9f), rimAlpha = 0.82f)
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.lyrics_card_title),
                color = VyoraText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.64f)
                    .aspectRatio(LyricsCardRenderer.WIDTH / LyricsCardRenderer.HEIGHT.toFloat())
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black)
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = card, animationSpec = tween(260), label = "lyricsCardPreview") { bmp ->
                    if (bmp == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = VyoraAccent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        }
                    } else {
                        Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                if (rendering && card != null) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            BackgroundPicker(
                selected = style,
                onSelect = { s ->
                    if (s == LyricsCardStyle.PHOTO && photo == null) pickPhoto() else style = s
                },
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                if (style == LyricsCardStyle.PHOTO) {
                    TextButton(onClick = { pickPhoto() }) {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = VyoraAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.lyrics_card_change_photo), color = VyoraAccent, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Text(
                        stringResource(R.string.lyrics_card_glass_hint),
                        color = VyoraTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = VyoraText) }
                TextButton(
                    enabled = card != null && !busy && !rendering,
                    onClick = {
                        val bmp = card ?: return@TextButton
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { LyricsCardRenderer.saveToGallery(context, bmp) }
                            busy = false
                            android.widget.Toast.makeText(context, if (ok) saved else saveFailed, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text(stringResource(R.string.lyrics_card_save), color = VyoraText) }
                TextButton(
                    enabled = card != null && !busy && !rendering,
                    onClick = {
                        val bmp = card ?: return@TextButton
                        busy = true
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) { runCatching { LyricsCardRenderer.writeShareFile(context, bmp) }.getOrNull() }
                            busy = false
                            if (uri != null) {
                                runCatching { LyricsCardRenderer.share(context, uri, shareTitle) }
                                onDismiss()
                            } else {
                                android.widget.Toast.makeText(context, saveFailed, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.lyrics_card_share), color = VyoraAccent, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Segmented control for the card background, Apple-style: one pill slides under the chosen segment. */
@Composable
private fun BackgroundPicker(selected: LyricsCardStyle, onSelect: (LyricsCardStyle) -> Unit) {
    val colors = LocalVyoraColors.current
    val outer = RoundedCornerShape(14.dp)
    val inner = RoundedCornerShape(11.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(if (colors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f))
            .padding(3.dp),
    ) {
        LyricsCardStyle.entries.forEach { s ->
            val on = s == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(inner)
                    .then(if (on) Modifier.background(if (colors.isDark) Color.White.copy(alpha = 0.18f) else Color.White) else Modifier)
                    .glassClickable(pressedScale = 0.96f) { onSelect(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(
                        when (s) {
                            LyricsCardStyle.ARTWORK -> R.string.lyrics_card_style_artwork
                            LyricsCardStyle.PHOTO -> R.string.lyrics_card_style_photo
                            LyricsCardStyle.COLOR -> R.string.lyrics_card_style_color
                            LyricsCardStyle.DARK -> R.string.lyrics_card_style_dark
                        }
                    ),
                    color = if (on) VyoraText else VyoraTextMuted,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
