package com.vyora.music.playback.source

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import com.vyora.music.data.settings.SettingsRepository
import java.util.TreeSet

/**
 * Disk cache for streamed audio (and the moving artwork of music videos).
 *
 * Replaying a song, seeking back, or a crossfade re-opening a track reads from here instead
 * of the network, which also means no fresh YouTube URL is needed mid-song. The size limit
 * follows the Storage setting live; old songs are evicted first.
 */
@OptIn(UnstableApi::class)
object MediaCache {
    private const val DIRECTORY = "media_cache"

    // Starts at the saved Storage limit, so a cache opened before the playback service (the
    // video artwork, say) does not grow to the default size first.
    private val evictor = AdjustableLruEvictor(SettingsRepository.current.mediaCache.bytes)

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): Cache {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: SimpleCache(
                context.applicationContext.cacheDir.resolve(DIRECTORY),
                evictor,
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
    }

    fun setMaxBytes(bytes: Long) {
        evictor.maxBytes = bytes
        cache?.let { evictor.trim(it) }
    }

    /** Bytes on disk right now (0 before anything was played). */
    fun usedBytes(context: Context): Long = get(context).cacheSpace

    /** Blocking: deletes every cached song. Call off the main thread. */
    fun clear(context: Context) {
        val c = get(context)
        c.keys.toList().forEach { c.removeResource(it) }
    }
}

/**
 * Least-recently-used eviction like Media3's own evictor, but the limit can change while
 * the cache is open (a [SimpleCache] folder can only be opened once per process).
 */
@OptIn(UnstableApi::class)
private class AdjustableLruEvictor(@Volatile var maxBytes: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(
        compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position }
    )
    private var currentSize = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(spans) {
            spans.add(span)
            currentSize += span.length
        }
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(spans) {
            if (spans.remove(span)) currentSize -= span.length
        }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun trim(cache: Cache) = evict(cache, 0)

    private fun evict(cache: Cache, incoming: Long) {
        while (true) {
            val oldest = synchronized(spans) {
                if (currentSize + incoming <= maxBytes || spans.isEmpty()) null else spans.first()
            } ?: return
            runCatching { cache.removeSpan(oldest) }
            // removeSpan reports back through onSpanRemoved; if the cache no longer knew the
            // span it does not, so drop it here or this loop would never end.
            synchronized(spans) { if (spans.remove(oldest)) currentSize -= oldest.length }
        }
    }
}
