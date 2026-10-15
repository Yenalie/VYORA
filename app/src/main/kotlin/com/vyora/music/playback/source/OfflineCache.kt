package com.vyora.music.playback.source

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache

@OptIn(UnstableApi::class)
object OfflineCache {
    private const val DIRECTORY = "offline_audio"
    private const val KEY_PREFIX = "yt-offline/"

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    var completed: Set<String> = emptySet()

    fun keyFor(videoId: String): String = KEY_PREFIX + videoId

    fun isComplete(videoId: String): Boolean = videoId in completed

    fun get(context: Context): Cache {
        cache?.let { return it }
        val db = MediaCache.database(context)
        return synchronized(this) {
            cache ?: SimpleCache(
                context.applicationContext.filesDir.resolve(DIRECTORY),
                NoOpCacheEvictor(),
                db,
            ).also { cache = it }
        }
    }

    fun usedBytes(context: Context): Long = get(context).cacheSpace
}
