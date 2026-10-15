package com.vyora.music.data.youtube

import android.net.Uri
import android.util.Log
import com.vyora.music.data.settings.AudioQuality
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/** A rendition ready to open: the URL plus the HTTP headers YouTube expects for it. */
data class ResolvedStream(
    val videoId: String,
    val itag: Int,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String,
)

/**
 * Turns a video id into playable stream URLs.
 *
 * One NewPipe extraction per video serves both the audio (playback service) and the moving
 * artwork (Now Playing), and is reused until shortly before YouTube's `expire` time. Every
 * URL is returned with the User-Agent of the YouTube client it was issued to: a URL handed
 * to the Android or iOS app answers 403 to a desktop browser, which used to surface as songs
 * failing (and being retried) at random.
 */
object StreamResolver {
    private const val TAG = "VYORA-Streams"

    /** Re-extract this long before YouTube's own expiry, so a long song never outlives its URL. */
    private const val EXPIRY_MARGIN_MS = 20L * 60 * 1000

    /** Used when a URL carries no `expire` parameter. */
    private const val DEFAULT_TTL_MS = 4L * 60 * 60 * 1000
    private const val MAX_ENTRIES = 64

    private class Entry(
        val audio: List<AudioCandidate>,
        val video: List<VideoCandidate>,
        val expiresAt: Long,
    )

    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }
    private val locks = ConcurrentHashMap<String, Any>()

    /** Blocking: runs on ExoPlayer's loader thread or [kotlinx.coroutines.Dispatchers.IO]. */
    @Throws(IOException::class)
    fun resolveAudio(videoId: String, quality: AudioQuality, preferredItag: Int? = null): ResolvedStream {
        val pick = StreamSelector.pickAudio(entry(videoId).audio, quality, preferredItag)
            ?: throw IOException("No playable audio stream for $videoId")
        return ResolvedStream(videoId, pick.itag, pick.url, headersFor(pick.url), pick.mimeType)
    }

    /** Blocking. Null when the video has no usable video-only rendition. */
    @Throws(IOException::class)
    fun resolveVideo(videoId: String): ResolvedStream? {
        val pick = StreamSelector.pickVideo(entry(videoId).video) ?: return null
        return ResolvedStream(videoId, pick.itag, pick.url, headersFor(pick.url), pick.mimeType)
    }

    /** Forget the extraction, e.g. after YouTube answered 403/410 for one of its URLs. */
    fun invalidate(videoId: String) {
        synchronized(entries) { entries.remove(videoId) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun entry(videoId: String): Entry {
        cached(videoId)?.let { return it }
        // One extraction per video even when audio and artwork ask at the same moment.
        val lock = locks.getOrPut(videoId) { Any() }
        synchronized(lock) {
            cached(videoId)?.let { return it }
            val fresh = try {
                extract(videoId)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                // NewPipe throws its own checked exceptions; ExoPlayer expects IOException.
                throw IOException("Stream extraction failed for $videoId", e)
            } finally {
                locks.remove(videoId)
            }
            synchronized(entries) { entries[videoId] = fresh }
            return fresh
        }
    }

    private fun cached(videoId: String): Entry? = synchronized(entries) {
        entries[videoId]?.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    private fun extract(videoId: String): Entry {
        val info = StreamInfo.getInfo(SimpleItem.watchUrl(videoId))
        val audio = info.audioStreams.map { it.toCandidate() }
        val video = info.videoOnlyStreams.map { it.toCandidate() }
        val urls = audio.map { it.url } + video.map { it.url }
        val expiry = urls.mapNotNull(::expiryOf).minOrNull()
        val now = System.currentTimeMillis()
        val expiresAt = expiry?.let { it - EXPIRY_MARGIN_MS }?.coerceAtLeast(now + EXPIRY_MARGIN_MS) ?: (now + DEFAULT_TTL_MS)
        Log.d(TAG, "$videoId: ${audio.size} audio, ${video.size} video renditions")
        return Entry(audio, video, expiresAt)
    }

    private fun AudioStream.toCandidate() = AudioCandidate(
        itag = itag,
        url = content.orEmpty(),
        bitrate = averageBitrate.takeIf { it > 0 } ?: bitrate,
        codec = codec.orEmpty(),
        mimeType = format?.mimeType.orEmpty(),
        isDrc = itagItem?.isDrc() == true || isDrcUrl(content),
        trackKind = when (audioTrackType) {
            null, AudioTrackType.ORIGINAL -> TrackKind.ORIGINAL
            else -> TrackKind.SECONDARY
        },
        progressive = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
        contentLength = itagItem?.contentLength ?: -1L,
    )

    private fun VideoStream.toCandidate() = VideoCandidate(
        itag = itag,
        url = content.orEmpty(),
        height = height,
        bitrate = bitrate,
        codec = codec.orEmpty(),
        mimeType = format?.mimeType.orEmpty(),
        progressive = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
    )

    /** `xtags=drc=1` marks the compressed rendition in the URL itself. */
    private fun isDrcUrl(url: String?): Boolean =
        url != null && runCatching { Uri.parse(url).getQueryParameter("xtags")?.contains("drc=1") == true }.getOrDefault(false)

    private fun expiryOf(url: String): Long? =
        runCatching { Uri.parse(url).getQueryParameter("expire")?.toLong()?.times(1000) }.getOrNull()

    /** The headers of the YouTube client [url] was issued to. */
    fun headersFor(url: String): Map<String, String> {
        val localization = NewPipe.getPreferredLocalization()
        val agent = runCatching {
            when {
                YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(localization)
                YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(localization)
                YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(localization)
                else -> null
            }
        }.getOrNull()
        return if (agent != null) {
            mapOf(HEADER_USER_AGENT to agent)
        } else {
            mapOf(
                HEADER_USER_AGENT to YouTubeConfig.BROWSER_USER_AGENT,
                HEADER_ORIGIN to YouTubeConfig.WEB_ORIGIN,
                HEADER_REFERER to YouTubeConfig.WEB_ORIGIN + "/",
            )
        }
    }

    private const val HEADER_USER_AGENT = "User-Agent"
    private const val HEADER_ORIGIN = "Origin"
    private const val HEADER_REFERER = "Referer"
}
