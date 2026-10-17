package com.vyora.music.ui.player

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import com.vyora.music.ui.design.liquidFill
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LyricsCardStyle { ARTWORK, COLOR, DARK }

object LyricsCardRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val MAX_LINES = 6

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

    fun render(
        art: Bitmap?,
        title: String,
        artist: String,
        lines: List<String>,
        style: LyricsCardStyle,
    ): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val tint = dominantColor(art)
        drawBackground(canvas, art, tint, style)

        val marginX = 84f
        val cardWidth = WIDTH - marginX * 2
        val pad = 64f
        val artSize = 132f
        val textWidth = (cardWidth - pad * 2).toInt()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xC8FFFFFF.toInt()
            textSize = 34f
            typeface = Typeface.DEFAULT
        }
        val headerTextWidth = textWidth - artSize - 32f
        val titleText = TextUtils.ellipsize(title, titlePaint, headerTextWidth, TextUtils.TruncateAt.END).toString()
        val artistText = TextUtils.ellipsize(artist, artistPaint, headerTextWidth, TextUtils.TruncateAt.END).toString()

        val lyric = lines.joinToString("\n") { it.trim() }
        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val maxLyricHeight = HEIGHT * 0.52f
        var size = 66f
        var layout: StaticLayout
        while (true) {
            lyricPaint.textSize = size
            layout = StaticLayout.Builder.obtain(lyric, 0, lyric.length, lyricPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.18f)
                .setIncludePad(false)
                .build()
            if (layout.height <= maxLyricHeight || size <= 38f) break
            size -= 4f
        }

        val footerHeight = 44f
        val cardHeight = pad + artSize + 56f + layout.height + 64f + footerHeight + pad * 0.8f
        val top = ((HEIGHT - cardHeight) / 2f).coerceAtLeast(120f)
        val card = RectF(marginX, top, marginX + cardWidth, top + cardHeight)
        val radius = 56f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4A000000 }
        canvas.drawRoundRect(RectF(card.left, card.top + 12f, card.right, card.bottom + 12f), radius, radius, shadow)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (style) {
                LyricsCardStyle.ARTWORK -> 0x37FFFFFF
                LyricsCardStyle.COLOR -> 0x39000000
                LyricsCardStyle.DARK -> ColorUtils.setAlphaComponent(tint, 0x72)
            }
        }
        canvas.drawRoundRect(card, radius, radius, fill)
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                card.left, card.top, card.right, card.bottom,
                intArrayOf(0x42FFFFFF, 0x16FFFFFF, 0x06FFFFFF, 0x18FFFFFF),
                floatArrayOf(0f, 0.38f, 0.76f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(card, radius, radius, sheen)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 2.2f
            color = 0x75FFFFFF
        }
        canvas.drawRoundRect(card, radius, radius, stroke)
        val inner = RectF(card.left + 5f, card.top + 5f, card.right - 5f, card.bottom - 5f)
        val innerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = 0x22FFFFFF
        }
        canvas.drawRoundRect(inner, radius - 5f, radius - 5f, innerStroke)
        val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            color = 0x68FFFFFF
        }
        canvas.drawLine(card.left + radius, card.top + 2f, card.right - radius, card.top + 2f, glint)

        val artRect = RectF(card.left + pad, card.top + pad, card.left + pad + artSize, card.top + pad + artSize)
        if (art != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(artRect, 22f, 22f, Path.Direction.CW) })
            canvas.drawBitmap(art, centerSquare(art), artRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
        } else {
            canvas.drawRoundRect(artRect, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        }
        val textLeft = artRect.right + 32f
        val titleBaseline = artRect.centerY() - 8f
        canvas.drawText(titleText, textLeft, titleBaseline, titlePaint)
        canvas.drawText(artistText, textLeft, titleBaseline + 48f, artistPaint)

        canvas.save()
        canvas.translate(card.left + pad, artRect.bottom + 56f)
        layout.draw(canvas)
        canvas.restore()

        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xD9FFFFFF.toInt()
            textSize = 30f
            letterSpacing = 0.32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val brandY = card.bottom - pad * 0.8f
        canvas.drawText("VYORA", card.left + pad, brandY, brand)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFA2D48.toInt() }
        canvas.drawCircle(card.left + pad + brand.measureText("VYORA") + 18f, brandY - 10f, 7f, dot)
        return out
    }

    private fun drawBackground(canvas: Canvas, art: Bitmap?, tint: Int, style: LyricsCardStyle) {
        val full = RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        when (style) {
            LyricsCardStyle.ARTWORK -> {
                if (art != null) {
                    val cropW = (art.height * 9 / 16).coerceAtMost(art.width).coerceAtLeast(1)
                    val crop = Bitmap.createBitmap(art, (art.width - cropW) / 2, 0, cropW, art.height)
                    val small = Bitmap.createScaledBitmap(crop, 12, 21, true)
                    val blurred = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(small, 36, 64, true), 108, 192, true)
                    canvas.drawBitmap(blurred, null, full, Paint(Paint.FILTER_BITMAP_FLAG))
                    canvas.drawRect(full, Paint().apply { color = 0x59000000 })
                } else {
                    gradient(canvas, full, tint)
                }
            }
            LyricsCardStyle.COLOR -> gradient(canvas, full, tint)
            LyricsCardStyle.DARK -> {
                canvas.drawRect(full, Paint().apply { color = 0xFF0E0E10.toInt() })
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, HEIGHT.toFloat(),
                        ColorUtils.setAlphaComponent(tint, 0x55), 0x00000000,
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(full, glow)
            }
        }
    }

    private fun gradient(canvas: Canvas, full: RectF, tint: Int) {
        val dark = ColorUtils.blendARGB(tint, 0xFF000000.toInt(), 0.55f)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, full.bottom, tint, dark, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(full, paint)
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
fun LyricsCardDialog(item: SimpleItem, lines: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(LyricsCardStyle.ARTWORK) }
    var art by remember { mutableStateOf<Bitmap?>(null) }
    var artLoaded by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.lyrics_card_share)
    val saved = stringResource(R.string.lyrics_card_saved)
    val saveFailed = stringResource(R.string.lyrics_card_save_failed)

    LaunchedEffect(item.id) {
        art = runCatching { LyricsCardRenderer.loadArtwork(context, item.thumbnail) }.getOrNull()
        artLoaded = true
    }
    LaunchedEffect(artLoaded, style) {
        if (!artLoaded) return@LaunchedEffect
        card = withContext(Dispatchers.Default) {
            LyricsCardRenderer.render(art, item.title, item.artist, lines, style)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(28.dp))
                .liquidFill(
                    shape = RoundedCornerShape(28.dp),
                    fill = VyoraGroupedCell.copy(alpha = 0.88f),
                    rimAlpha = 0.82f,
                )
                .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f), RoundedCornerShape(28.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.lyrics_card_title), color = VyoraText, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(LyricsCardRenderer.WIDTH / LyricsCardRenderer.HEIGHT.toFloat())
                    .clip(RoundedCornerShape(18.dp))
                    .background(androidx.compose.ui.graphics.Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = card, animationSpec = tween(240), label = "lyricsCardPreview") { bmp ->
                    if (bmp == null) {
                        CircularProgressIndicator(color = VyoraAccent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    } else {
                        Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LyricsCardStyle.entries.forEach { s ->
                    FilterChip(
                        selected = style == s,
                        onClick = { style = s },
                        label = {
                            Text(
                                stringResource(
                                    when (s) {
                                        LyricsCardStyle.ARTWORK -> R.string.lyrics_card_style_artwork
                                        LyricsCardStyle.COLOR -> R.string.lyrics_card_style_color
                                        LyricsCardStyle.DARK -> R.string.lyrics_card_style_dark
                                    }
                                )
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = VyoraTextMuted,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                            selectedContainerColor = VyoraAccent,
                        ),
                        border = if (style == s) null else BorderStroke(1.dp, VyoraTextMuted.copy(alpha = 0.4f)),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = VyoraText) }
                TextButton(
                    enabled = card != null && !busy,
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
                    enabled = card != null && !busy,
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
                ) { Text(stringResource(R.string.lyrics_card_share), color = VyoraAccent) }
            }
        }
    }
}
