package com.vyora.music.playback.source

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.vyora.music.data.settings.AudioQuality
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.youtube.ResolvedStream
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.StreamResolver
import java.io.IOException

/**
 * Data sources for YouTube items. A queue entry's URI is the watch URL; the real stream URL
 * is looked up right before ExoPlayer opens it, then read through [MediaCache].
 *
 * The cache key is per video (and per audio quality), not per stream URL, because YouTube
 * URLs change every few hours. The itag of the cached file is stored with it, and the next
 * resolve asks for the same itag again so a cached half and a downloaded half always come
 * from the same file. If YouTube no longer offers that itag the stale bytes are dropped.
 */
@OptIn(UnstableApi::class)
object YouTubeDataSource {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val META_ITAG = "vyora-itag"
    private const val KEY_AUDIO = "yt-audio"
    private const val KEY_VIDEO = "yt-video"

    /** What the playback service plays. */
    fun audioFactory(context: Context): DataSource.Factory = factory(context, Kind.AUDIO)

    /** What the moving artwork plays (video only, no sound). */
    fun videoFactory(context: Context): DataSource.Factory = factory(context, Kind.VIDEO)

    /** The URI to give the artwork player for [videoId]. */
    fun videoUri(videoId: String): Uri = Uri.parse(SimpleItem.watchUrl(videoId))

    /** `https://www.youtube.com/watch?v=ID` -> `ID`; null for anything else. */
    fun videoIdOf(uri: Uri?): String? {
        val host = uri?.host ?: return null
        if (!host.endsWith("youtube.com")) return null
        return uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
    }

    private enum class Kind { AUDIO, VIDEO }

    private fun factory(context: Context, kind: Kind): DataSource.Factory {
        // No User-Agent here: each request carries the one of the client its URL belongs to.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        val cache = MediaCache.get(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(http, Resolver(cache, kind)))
            .setCacheKeyFactory { spec -> cacheKey(spec, kind) }
            // A broken cache file is skipped, not fatal: the song streams instead.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun cacheKey(spec: DataSpec, kind: Kind): String {
        val id = videoIdOf(spec.uri) ?: return spec.key ?: spec.uri.toString()
        return when (kind) {
            Kind.AUDIO -> "$KEY_AUDIO/$id/${SettingsRepository.current.audioQuality.name}"
            Kind.VIDEO -> "$KEY_VIDEO/$id"
        }
    }

    private class Resolver(private val cache: Cache, private val kind: Kind) : ResolvingDataSource.Resolver {
        @Throws(IOException::class)
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val videoId = videoIdOf(dataSpec.uri) ?: return dataSpec // already a direct URL
            val key = dataSpec.key
            val cachedItag = key?.let { cache.getContentMetadata(it).get(META_ITAG, -1L).toInt() }?.takeIf { it > 0 }

            val stream: ResolvedStream = when (kind) {
                Kind.AUDIO -> StreamResolver.resolveAudio(videoId, qualityOf(key), cachedItag)
                Kind.VIDEO -> StreamResolver.resolveVideo(videoId) ?: throw IOException("No video rendition for $videoId")
            }

            if (key != null && cachedItag != stream.itag) {
                if (cachedItag != null) cache.removeResource(key)
                cache.applyContentMetadataMutations(
                    key,
                    ContentMetadataMutations()
                        .set(META_ITAG, stream.itag.toLong())
                        .remove(ContentMetadata.KEY_CONTENT_LENGTH),
                )
            }
            return dataSpec.buildUpon()
                .setUri(Uri.parse(stream.url))
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + stream.headers)
                .build()
        }

        private fun qualityOf(key: String?): AudioQuality =
            key?.substringAfterLast('/')?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
                ?: SettingsRepository.current.audioQuality
    }
}
