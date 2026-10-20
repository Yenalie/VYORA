package com.vyora.music.data.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One audio file from MediaStore, with what we could learn about its quality. */
data class LocalTrack(
    val item: SimpleItem,
    val album: String,
    val sizeBytes: Long,
    val format: AudioFormatInfo,
    val dateAdded: Long,
) {
    val tier: AudioTier get() = format.tier
}

/**
 * Songs stored on the device. Lossless files (FLAC, ALAC, WAV, AIFF) are what make this interesting:
 * YouTube Music only streams lossy Opus/AAC, so this is the only way to hear lossless audio in VYORA.
 */
object LocalLibrary {
    private const val TAG = "VYORA-Local"
    private const val ALAC_MIN_BITRATE = 400_000

    sealed interface State {
        data object Idle : State
        data object NoPermission : State
        data object Scanning : State
        data class Ready(val tracks: List<LocalTrack>) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Probed formats keyed by "id@dateModified" so a rescan doesn't reopen every file. */
    private val probed = ConcurrentHashMap<String, AudioFormatInfo>()

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun albumArtUri(albumId: Long): String = "content://media/external/audio/albumart/$albumId"

    @Volatile private var testTracks: List<LocalTrack>? = null

    /** Screenshot tests: show [tracks] instead of querying MediaStore (null restores normal scanning). */
    @androidx.annotation.VisibleForTesting
    internal fun replaceForTest(tracks: List<LocalTrack>?) {
        testTracks = tracks
        _state.value = tracks?.let { State.Ready(it) } ?: State.Idle
    }

    suspend fun refresh(context: Context) {
        testTracks?.let { _state.value = State.Ready(it); return }
        val app = context.applicationContext
        if (!hasPermission(app)) {
            _state.value = State.NoPermission
            return
        }
        if (_state.value !is State.Ready) _state.value = State.Scanning
        val tracks = withContext(Dispatchers.IO) { runCatching { query(app) }.onFailure { Log.w(TAG, "Scan failed", it) }.getOrDefault(emptyList()) }
        _state.value = State.Ready(tracks)
    }

    private fun query(context: Context): List<LocalTrack> {
        val resolver = context.contentResolver
        val columns = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.BITRATE)
            if (Build.VERSION.SDK_INT >= 34) {
                // MediaStore.Audio.Media.SAMPLERATE / BITS_PER_SAMPLE (API 34).
                add("samplerate")
                add("bits_per_sample")
            }
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 15000"
        val out = mutableListOf<LocalTrack>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, selection, null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val bitrateCol = c.getColumnIndex("bitrate")
            val rateCol = c.getColumnIndex("samplerate")
            val bitsCol = c.getColumnIndex("bits_per_sample")
            while (c.moveToNext()) {
                val mediaId = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                val storeFormat = AudioFormatInfo(
                    mimeType = c.getString(mimeCol).orEmpty(),
                    sampleRateHz = if (rateCol >= 0) c.getInt(rateCol) else 0,
                    bitDepth = if (bitsCol >= 0) c.getInt(bitsCol) else 0,
                    bitrate = if (bitrateCol >= 0) c.getInt(bitrateCol) else 0,
                )
                val key = "$mediaId@${c.getLong(modifiedCol)}"
                val format = probed[key] ?: refine(context, uri, storeFormat).also { probed[key] = it }
                val artist = c.getString(artistCol)?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty()
                val title = c.getString(titleCol).orEmpty().ifBlank { "Track $mediaId" }
                out += LocalTrack(
                    item = SimpleItem(
                        id = SimpleItem.localId(mediaId),
                        title = title,
                        artist = artist,
                        thumbnail = albumArtUri(c.getLong(albumIdCol)),
                        url = uri.toString(),
                        duration = c.getLong(durationCol) / 1000L,
                        type = ItemType.SONG,
                    ),
                    album = c.getString(albumCol)?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(),
                    sizeBytes = c.getLong(sizeCol),
                    format = format,
                    dateAdded = c.getLong(addedCol),
                )
            }
        }
        return out
    }

    /**
     * MediaStore's MIME type says "audio/mp4" for both AAC and ALAC, and only API 34+ reports sample rate
     * and bit depth. Read the file header when that information is missing.
     */
    private fun refine(context: Context, uri: Uri, store: AudioFormatInfo): AudioFormatInfo {
        val mime = store.mimeType.lowercase()
        val needsDetail = store.sampleRateHz <= 0 || store.bitDepth <= 0
        return when {
            "flac" in mime && needsDetail -> header(context, uri, AudioHeaders::parseFlac)?.copy(bitrate = store.bitrate) ?: store
            "wav" in mime && needsDetail -> header(context, uri, AudioHeaders::parseWav) ?: store
            "aif" in mime -> header(context, uri, AudioHeaders::parseAiff) ?: store
            "dsf" in mime || "dsd" in mime || mime == "audio/x-dff" -> header(context, uri, AudioHeaders::parseDsf) ?: store
            (mime == "audio/mp4" || "m4a" in mime) && (store.bitrate == 0 || store.bitrate >= ALAC_MIN_BITRATE) ->
                extractor(context, uri) ?: store
            else -> store
        }
    }

    private fun header(context: Context, uri: Uri, parse: (ByteArray) -> AudioFormatInfo?): AudioFormatInfo? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(AudioHeaders.PROBE_BYTES)
            var n = 0
            while (n < buffer.size) {
                val r = input.read(buffer, n, buffer.size - n)
                if (r < 0) break
                n += r
            }
            parse(buffer.copyOf(n))
        }
    }.getOrNull()

    private fun extractor(context: Context, uri: Uri): AudioFormatInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).asSequence()
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { f ->
                    val bits = if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmBits(f.getInteger(MediaFormat.KEY_PCM_ENCODING))
                    } else if (f.containsKey("bits-per-sample")) f.getInteger("bits-per-sample") else 0
                    AudioFormatInfo(
                        mimeType = f.getString(MediaFormat.KEY_MIME).orEmpty(),
                        sampleRateHz = f.intOr(MediaFormat.KEY_SAMPLE_RATE),
                        bitDepth = bits,
                        bitrate = f.intOr(MediaFormat.KEY_BIT_RATE),
                        channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT),
                    )
                }
        } catch (e: Exception) {
            Log.d(TAG, "Probe failed for $uri", e)
            null
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOr(key: String) = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    internal fun pcmBits(encoding: Int): Int = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }
}
