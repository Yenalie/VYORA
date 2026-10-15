package com.vyora.music.playback.video

import android.content.Context
import android.net.ConnectivityManager
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vyora.music.data.settings.VideoArtwork
import com.vyora.music.data.youtube.StreamResolver
import com.vyora.music.playback.source.YouTubeDataSource
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class VideoArtworkPlayer(context: Context) {
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(YouTubeDataSource.videoFactory(context)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, START_BUFFER_MS)
                .build()
        )
        .build()
        .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }

    private var videoId: String? = null
    private var retried = false

    private val _hasFrame = MutableStateFlow(false)
    val hasFrame: StateFlow<Boolean> = _hasFrame.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                _hasFrame.value = true
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = videoId ?: return
                if (retried) return
                retried = true
                StreamResolver.invalidate(id)
                player.prepare()
            }
        })
    }

    fun setTextureView(view: TextureView?) {
        if (view == null) player.clearVideoSurface() else player.setVideoTextureView(view)
    }

    fun load(id: String, startMs: Long, playing: Boolean) {
        if (id == videoId) return
        videoId = id
        retried = false
        _hasFrame.value = false
        player.setMediaItem(MediaItem.Builder().setMediaId(id).setUri(YouTubeDataSource.videoUri(id)).build(), startMs)
        player.playWhenReady = playing
        player.prepare()
    }

    fun follow(songPositionMs: Long, playing: Boolean) {
        if (videoId == null) return
        if (player.playWhenReady != playing) player.playWhenReady = playing
        if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
            val duration = player.duration
            val target = if (duration != C.TIME_UNSET && duration > 0) songPositionMs.coerceAtMost(duration) else songPositionMs
            if (abs(player.currentPosition - target) > MAX_DRIFT_MS) player.seekTo(target)
        }
    }

    fun stop() {
        videoId = null
        _hasFrame.value = false
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        player.release()
    }

    companion object {
        private const val MIN_BUFFER_MS = 4_000
        private const val MAX_BUFFER_MS = 15_000
        private const val START_BUFFER_MS = 1_000
        private const val MAX_DRIFT_MS = 300L

        fun allowed(context: Context, mode: VideoArtwork): Boolean = when (mode) {
            VideoArtwork.OFF -> false
            VideoArtwork.ALWAYS -> true
            VideoArtwork.WIFI_ONLY -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm != null && !cm.isActiveNetworkMetered
            }
        }
    }
}
