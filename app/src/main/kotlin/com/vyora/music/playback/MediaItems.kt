package com.vyora.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem

/**
 * SimpleItem <-> MediaItem, shared by the UI ([PlayerManager]) and the playback service
 * (autoplay adds songs on its own, with the screen off).
 */
object MediaItems {
    const val EXTRA_DURATION_SEC = "vyora_duration_sec"
    const val EXTRA_ITEM_TYPE = "vyora_item_type"
    /** Added by Autoplay (similar songs after the queue), not by the user. */
    const val EXTRA_AUTOPLAY = "vyora_autoplay"
    const val EXTRA_ARTIST_ID = "vyora_artist_id"
    /** A music video: Now Playing shows its moving picture. */
    const val EXTRA_VIDEO = "vyora_video"

    fun from(item: SimpleItem, autoplay: Boolean = false): MediaItem {
        val extras = Bundle().apply {
            putLong(EXTRA_DURATION_SEC, item.duration)
            putString(EXTRA_ITEM_TYPE, item.type)
            putBoolean(EXTRA_AUTOPLAY, autoplay)
            putBoolean(EXTRA_VIDEO, item.isVideo)
            item.artistId?.let { putString(EXTRA_ARTIST_ID, it) }
        }
        return MediaItem.Builder()
            .setMediaId(item.id)
            // The service's ResolvingDataSource swaps this for the real audio URL on load.
            .setUri(SimpleItem.watchUrl(item.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setArtworkUri(item.thumbnail?.let { Uri.parse(it) })
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    fun toSimpleItem(media: MediaItem): SimpleItem? {
        val id = media.mediaId
        if (id.isBlank() || id == MediaItem.DEFAULT_MEDIA_ID) return null
        val md = media.mediaMetadata
        return SimpleItem(
            id = id,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            thumbnail = md.artworkUri?.toString(),
            url = SimpleItem.watchUrl(id),
            duration = md.extras?.getLong(EXTRA_DURATION_SEC, 0L) ?: 0L,
            type = md.extras?.getString(EXTRA_ITEM_TYPE) ?: ItemType.SONG,
            artistId = md.extras?.getString(EXTRA_ARTIST_ID),
            isVideo = md.extras?.getBoolean(EXTRA_VIDEO, false) == true,
        )
    }

    fun isAutoplay(media: MediaItem): Boolean = media.mediaMetadata.extras?.getBoolean(EXTRA_AUTOPLAY, false) == true
}
