package com.vyora.music.data.download

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.playback.source.OfflineCache

/**
 * "Export downloads to Music/VYORA" (BitChord's export of compatible downloads): a finished download is
 * copied out of VYORA's private offline store into the shared Music folder, as the file it really is
 * (Opus/WebM, AAC/M4A, MP3, or a server's original FLAC/ALAC/WAV), so other music apps can play it.
 *
 * The private copy stays: it is what VYORA plays offline, and removing a download in VYORA leaves the
 * exported file alone — once it is in Music it belongs to the user.
 */
@OptIn(UnstableApi::class)
object DownloadExport {
    private const val TAG = "VYORA-Export"
    private const val PREFS = "vyora_exports"
    const val FOLDER = "Music/VYORA"

    data class Container(val extension: String, val mime: String)

    fun isExported(context: Context, id: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(id)

    /** Copies [item]'s completed download into Music/VYORA. Blocking; call off the main thread. */
    fun export(context: Context, item: SimpleItem): Boolean {
        val app = context.applicationContext
        if (isExported(app, item.id)) return true
        val cache = OfflineCache.get(app)
        val spans = cache.getCachedSpans(OfflineCache.keyFor(item.id)).sortedBy { it.position }
        if (spans.isEmpty() || spans.any { !it.isCached || it.file == null }) return false
        var expected = 0L
        for (span in spans) {
            if (span.position != expected) return false
            expected += span.length
        }
        val head = ByteArray(12)
        val read = spans.first().file!!.inputStream().use { it.read(head) }
        val container = sniff(head.copyOf(read.coerceAtLeast(0))) ?: return false

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(item, container))
            put(MediaStore.MediaColumns.MIME_TYPE, container.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Audio.AudioColumns.TITLE, item.title)
            put(MediaStore.Audio.AudioColumns.ARTIST, item.artist)
            put(MediaStore.Audio.AudioColumns.IS_MUSIC, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = app.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                spans.forEach { span -> span.file!!.inputStream().use { it.copyTo(out) } }
            } ?: error("No output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(item.id, uri.toString()).apply()
            true
        }.getOrElse {
            Log.w(TAG, "Export failed for ${item.id}", it)
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    internal fun fileName(item: SimpleItem, container: Container): String {
        val base = listOf(item.artist, item.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { item.id }
        val safe = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120)
        return "$safe.${container.extension}"
    }

    /** What the bytes really are; the extension follows the content, not where it came from. */
    internal fun sniff(head: ByteArray): Container? {
        fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it].toInt() and 0xFF == b[it] }
        return when {
            at(0, 0x66, 0x4C, 0x61, 0x43) -> Container("flac", "audio/flac")
            at(0, 0x4F, 0x67, 0x67, 0x53) -> Container("ogg", "audio/ogg")
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> Container("webm", "audio/webm")
            at(4, 0x66, 0x74, 0x79, 0x70) -> Container("m4a", "audio/mp4")
            at(0, 0x52, 0x49, 0x46, 0x46) -> Container("wav", "audio/wav")
            at(0, 0x46, 0x4F, 0x52, 0x4D) -> Container("aiff", "audio/aiff")
            at(0, 0x49, 0x44, 0x33) -> Container("mp3", "audio/mpeg")
            head.size >= 2 && head[0].toInt() and 0xFF == 0xFF && head[1].toInt() and 0xE0 == 0xE0 -> Container("mp3", "audio/mpeg")
            else -> null
        }
    }

}
