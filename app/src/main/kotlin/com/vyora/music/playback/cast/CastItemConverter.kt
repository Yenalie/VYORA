package com.vyora.music.playback.cast

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.playback.MediaItems
import org.json.JSONObject

@OptIn(UnstableApi::class)
class CastItemConverter(private val streamUrl: (String) -> String) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val item = MediaItems.toSimpleItem(mediaItem) ?: SimpleItem(
            id = mediaItem.mediaId,
            title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
            artist = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
            thumbnail = mediaItem.mediaMetadata.artworkUri?.toString(),
            url = SimpleItem.watchUrl(mediaItem.mediaId),
        )
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            putString(MediaMetadata.KEY_ARTIST, item.artist)
            item.thumbnail?.takeIf { it.startsWith("http") }?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val custom = JSONObject()
            .put(KEY_ID, item.id)
            .put(KEY_TITLE, item.title)
            .put(KEY_ARTIST, item.artist)
            .put(KEY_THUMB, item.thumbnail.orEmpty())
            .put(KEY_DURATION, item.duration)
            .put(KEY_ARTIST_ID, item.artistId.orEmpty())
            .put(KEY_VIDEO, item.isVideo)
            .put(KEY_AUTOPLAY, MediaItems.isAutoplay(mediaItem))
        val info = MediaInfo.Builder(item.id)
            .setContentUrl(streamUrl(item.id))
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .apply { if (item.duration > 0) setStreamDuration(item.duration * 1000L) }
            .build()
        return MediaQueueItem.Builder(info).setCustomData(custom).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val custom = mediaQueueItem.customData ?: mediaQueueItem.media?.customData ?: JSONObject()
        val id = custom.optString(KEY_ID).ifBlank { mediaQueueItem.media?.contentId.orEmpty() }
        val item = SimpleItem(
            id = id,
            title = custom.optString(KEY_TITLE),
            artist = custom.optString(KEY_ARTIST),
            thumbnail = custom.optString(KEY_THUMB).takeIf { it.isNotBlank() },
            url = SimpleItem.watchUrl(id),
            duration = custom.optLong(KEY_DURATION),
            artistId = custom.optString(KEY_ARTIST_ID).takeIf { it.isNotBlank() },
            isVideo = custom.optBoolean(KEY_VIDEO),
        )
        return MediaItems.from(item, autoplay = custom.optBoolean(KEY_AUTOPLAY))
    }

    private companion object {
        const val CONTENT_TYPE = "audio/mp4"
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_THUMB = "thumb"
        const val KEY_DURATION = "duration"
        const val KEY_ARTIST_ID = "artistId"
        const val KEY_VIDEO = "video"
        const val KEY_AUTOPLAY = "autoplay"
    }
}
