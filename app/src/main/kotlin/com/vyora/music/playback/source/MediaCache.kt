package com.vyora.music.playback.source

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import com.vyora.music.data.settings.SettingsRepository
import java.util.TreeSet

@OptIn(UnstableApi::class)
object MediaCache {
    private const val DIRECTORY = "media_cache"

    private val evictor = AdjustableLruEvictor(SettingsRepository.current.mediaCache.bytes)

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var database: StandaloneDatabaseProvider? = null

    fun database(context: Context): DatabaseProvider {
        database?.let { return it }
        return synchronized(this) {
            database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }
        }
    }

    fun get(context: Context): Cache {
        cache?.let { return it }
        val db = database(context)
        return synchronized(this) {
            cache ?: SimpleCache(
                context.applicationContext.cacheDir.resolve(DIRECTORY),
                evictor,
                db,
            ).also { cache = it }
        }
    }

    fun setMaxBytes(bytes: Long) {
        evictor.maxBytes = bytes
        cache?.let { evictor.trim(it) }
    }

    fun usedBytes(context: Context): Long = get(context).cacheSpace

    fun clear(context: Context) {
        val c = get(context)
        c.keys.toList().forEach { c.removeResource(it) }
    }
}

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
            synchronized(spans) { if (spans.remove(oldest)) currentSize -= oldest.length }
        }
    }
}
