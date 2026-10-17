package com.vyora.music.playback.source

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.vyora.music.data.settings.AudioQuality
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.settings.effectiveQuality
import com.vyora.music.data.youtube.ResolvedStream
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.StreamResolver
import java.io.IOException

@OptIn(UnstableApi::class)
object YouTubeDataSource {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val META_ITAG = "vyora-itag"
    private const val KEY_AUDIO = "yt-audio"
    private const val KEY_VIDEO = "yt-video"
    private const val NO_OFFLINE = "yt-none/"
    private const val KEY_SERVER = "srv-audio"

    /** What a download keeps right now: the user's Download Quality rung. */
    val offlineQuality: AudioQuality get() = SettingsRepository.current.downloadQuality.stream

    private fun streamingQuality(): AudioQuality =
        SettingsRepository.current.effectiveQuality(com.vyora.music.core.NetworkState.isMetered)

    /** Offline key of a playable URI: a YouTube watch page or one of our server stream URLs. */
    fun offlineIdOf(uri: Uri?): String? = videoIdOf(uri)
        ?: com.vyora.music.data.server.MusicServer.streamSongIdOf(uri?.toString())
            ?.let { com.vyora.music.data.server.MusicServer.SONG_PREFIX + it }

    /**
     * Audio for the player, Crossfader and Cast proxy. `https` (YouTube) goes through the stream cache;
     * `content://` and `file://` (lossless files on the device) are read directly, never cached.
     */
    fun audioFactory(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext, factory(context, Kind.AUDIO))

    fun videoFactory(context: Context): DataSource.Factory = factory(context, Kind.VIDEO)

    fun videoUri(videoId: String): Uri = Uri.parse(SimpleItem.watchUrl(videoId))

    fun videoIdOf(uri: Uri?): String? {
        val host = uri?.host ?: return null
        if (!host.endsWith("youtube.com")) return null
        return uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
    }

    private enum class Kind { AUDIO, VIDEO }

    fun downloadUpstreamFactory(context: Context): DataSource.Factory =
        ResolvingDataSource.Factory(http(), Resolver(OfflineCache.get(context), Kind.AUDIO) { offlineQuality })

    private fun http(): DataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(READ_TIMEOUT_MS)

    private fun factory(context: Context, kind: Kind): DataSource.Factory {
        val cache = MediaCache.get(context)
        val streaming = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(http(), Resolver(cache, kind)))
            .setCacheKeyFactory { spec -> cacheKey(spec, kind) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (kind != Kind.AUDIO) return streaming
        return CacheDataSource.Factory()
            .setCache(OfflineCache.get(context))
            .setUpstreamDataSourceFactory(streaming)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory { spec -> offlineKey(spec) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun offlineKey(spec: DataSpec): String {
        val id = offlineIdOf(spec.uri) ?: return NO_OFFLINE + (spec.key ?: spec.uri.toString())
        return if (OfflineCache.isComplete(id)) OfflineCache.keyFor(id) else NO_OFFLINE + id
    }

    private fun cacheKey(spec: DataSpec, kind: Kind): String {
        if (kind == Kind.AUDIO) {
            com.vyora.music.data.server.MusicServer.streamSongIdOf(spec.uri.toString())?.let { song ->
                return "$KEY_SERVER/$song/${serverRung(streamingQuality())}"
            }
        }
        val id = videoIdOf(spec.uri) ?: return spec.key ?: spec.uri.toString()
        return when (kind) {
            Kind.AUDIO -> "$KEY_AUDIO/$id/${streamingQuality().name}"
            Kind.VIDEO -> "$KEY_VIDEO/$id"
        }
    }

    /** Cache rung of a server stream: rungs that ask for the same file share one cached copy. */
    private fun serverRung(q: AudioQuality): String = q.serverKbps?.toString() ?: "raw"

    private class Resolver(
        private val cache: Cache,
        private val kind: Kind,
        private val fixedQuality: (() -> AudioQuality)? = null,
    ) : ResolvingDataSource.Resolver {
        @Throws(IOException::class)
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            if (kind == Kind.AUDIO && com.vyora.music.data.server.MusicServer.streamSongIdOf(dataSpec.uri.toString()) != null) {
                val q = fixedQuality?.invoke() ?: qualityOfServerKey(dataSpec.key) ?: streamingQuality()
                val url = com.vyora.music.data.server.MusicServer.withQuality(dataSpec.uri.toString(), q.serverKbps)
                return dataSpec.buildUpon().setUri(Uri.parse(url)).build()
            }
            val videoId = videoIdOf(dataSpec.uri) ?: return dataSpec
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

        /** The rung a server cache key was written for, so a cached copy is completed at the same rung. */
        private fun qualityOfServerKey(key: String?): AudioQuality? {
            if (key == null || !key.startsWith("$KEY_SERVER/")) return null
            val rung = key.substringAfterLast('/')
            return AudioQuality.entries.firstOrNull { serverRung(it) == rung }
        }

        private fun qualityOf(key: String?): AudioQuality = fixedQuality?.invoke() ?:
            key?.substringAfterLast('/')?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
                ?: SettingsRepository.current.effectiveQuality(com.vyora.music.core.NetworkState.isMetered)
    }
}
