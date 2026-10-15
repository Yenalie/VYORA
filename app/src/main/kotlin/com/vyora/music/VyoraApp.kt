package com.vyora.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.vyora.music.data.account.SyncEngine
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.library.SearchHistory

/**
 * Starts the library database and the YouTube account sync, and sets up Coil.
 *
 * Coil: Without it Coil uses its default disk cache (2% of the device
 * storage, up to 250 MB), and with 544px list covers plus 1080px player / album covers that
 * fills up fast. Here the disk cache is capped at [DISK_CACHE_MB] and old covers are evicted
 * first (LRU), so storage stays flat however much the user browses.
 */
class VyoraApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Room-backed stores first, then the account (which may start a background sync).
        LibraryManager.init(this)
        SearchHistory.init(this)
        SyncEngine.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(DISK_CACHE_MB * 1024L * 1024L)
                .build()
        }
        .crossfade(true)
        .build()

    companion object {
        const val DISK_CACHE_MB = 80L
    }
}
