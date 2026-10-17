package com.vyora.music.data.youtube

import android.net.Uri
import android.util.Log
import com.vyora.music.data.settings.AudioQuality
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

data class ResolvedStream(
    val videoId: String,
    val itag: Int,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String,
)

object StreamResolver {
    private const val TAG = "VYORA-Streams"

    private const val EXPIRY_MARGIN_MS = 20L * 60 * 1000

    private const val DEFAULT_TTL_MS = 4L * 60 * 60 * 1000
    private const val MAX_ENTRIES = 64

    private class Entry(
        val audio: List<AudioCandidate>,
        val video: List<VideoCandidate>,
        val expiresAt: Long,
    )

    /**
     * YouTube's normalization figure for [videoId], once its stream has been resolved; `null` until then.
     * Safe to call from the audio thread.
     */
    fun loudnessDbFor(videoId: String): Double? = LoudnessStore.get(videoId)

    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }
    private val locks = ConcurrentHashMap<String, Any>()
    private val picked = object : LinkedHashMap<String, AudioCandidate>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioCandidate>) = size > MAX_ENTRIES
    }.let { java.util.Collections.synchronizedMap(it) }

    /** The rendition most recently chosen for [videoId] (codec + bitrate for the Now Playing badge). */
    fun pickedAudio(videoId: String): AudioCandidate? = picked[videoId]

    @Throws(IOException::class)
    fun resolveAudio(videoId: String, quality: AudioQuality, preferredItag: Int? = null): ResolvedStream {
        val pick = StreamSelector.pickAudio(entry(videoId).audio, quality, preferredItag)
            ?: throw IOException("No playable audio stream for $videoId")
        picked[videoId] = pick
        return ResolvedStream(videoId, pick.itag, pick.url, headersFor(pick.url), pick.mimeType)
    }

    @Throws(IOException::class)
    fun resolveVideo(videoId: String): ResolvedStream? {
        val pick = StreamSelector.pickVideo(entry(videoId).video) ?: return null
        return ResolvedStream(videoId, pick.itag, pick.url, headersFor(pick.url), pick.mimeType)
    }

    fun invalidate(videoId: String) {
        synchronized(entries) { entries.remove(videoId) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun entry(videoId: String): Entry {
        cached(videoId)?.let { return it }
        val lock = locks.getOrPut(videoId) { Any() }
        synchronized(lock) {
            cached(videoId)?.let { return it }
            try {
                val fresh = try {
                    extract(videoId)
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Stream extraction failed for $videoId", e)
                }
                // Publish before releasing the lock so a caller arriving now hits the cache, not a new extraction.
                synchronized(entries) { entries[videoId] = fresh }
                return fresh
            } finally {
                locks.remove(videoId, lock)
            }
        }
    }

    private fun cached(videoId: String): Entry? = synchronized(entries) {
        entries[videoId]?.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    private fun extract(videoId: String): Entry {
        val extractor = ServiceList.YouTube.getStreamExtractor(SimpleItem.watchUrl(videoId))
        val info = StreamInfo.getInfo(extractor)
        Loudness.read(extractor)?.let { db -> LoudnessStore.put(videoId, db) }
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
        // NewPipe's averageBitrate is the itag's nominal rate in *kbps* (160 for itag 251) while bitrate is
        // in bps. Comparing the kbps figure against bps thresholds put every rendition under the Medium and
        // Low caps, so all four quality rungs played the same stream. Normalise to bps here.
        bitrate = averageBitrate.takeIf { it > 0 }?.let { if (it < 10_000) it * 1000 else it } ?: bitrate,
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

    private fun isDrcUrl(url: String?): Boolean =
        url != null && runCatching { Uri.parse(url).getQueryParameter("xtags")?.contains("drc=1") == true }.getOrDefault(false)

    private fun expiryOf(url: String): Long? =
        runCatching { Uri.parse(url).getQueryParameter("expire")?.toLong()?.times(1000) }.getOrNull()

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
