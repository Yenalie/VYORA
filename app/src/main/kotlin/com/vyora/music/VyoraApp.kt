package com.vyora.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.vyora.music.data.account.SyncEngine
import com.vyora.music.data.download.Downloads
import com.vyora.music.data.download.OfflineArtInterceptor
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.library.SearchHistory
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.update.UpdateManager

class VyoraApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        LibraryManager.init(this)
        SearchHistory.init(this)
        SyncEngine.init(this)
        SettingsRepository.init(this)
        UpdateManager.init(this)
        Downloads.init(this)
        com.vyora.music.data.discord.DiscordAccount.init(this)
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
        .components { add(OfflineArtInterceptor(this@VyoraApp)) }
        .crossfade(true)
        .build()

    companion object {
        const val DISK_CACHE_MB = 80L
    }
}
