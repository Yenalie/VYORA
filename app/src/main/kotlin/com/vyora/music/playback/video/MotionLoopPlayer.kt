package com.vyora.music.playback.video

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vyora.music.playback.source.MediaCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays a short, silent artwork loop (Apple Music motion art, Spotify Canvas) forever. Loops go through the
 * shared media cache, so after the first pass every repeat, and every later visit to the same song, is read
 * from disk instead of the network.
 */
@OptIn(UnstableApi::class)
class MotionLoopPlayer(context: Context) {
    private val dataSource = CacheDataSource.Factory()
        .setCache(MediaCache.get(context))
        .setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_500, 12_000, 600, 600).build())
        .build()
        .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }

    private var url: String? = null

    private val _hasFrame = MutableStateFlow(false)
    val hasFrame: StateFlow<Boolean> = _hasFrame.asStateFlow()

    private val _failed = MutableStateFlow(false)
    /** The loop could not be played (bad link, offline before the first pass was cached). */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val _aspect = MutableStateFlow(0f)
    /** Real width / height of the decoded video, 0 until known. */
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { _hasFrame.value = true }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _aspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
            override fun onPlayerError(error: PlaybackException) { _failed.value = true }
        })
    }

    fun setTextureView(view: TextureView?) {
        if (view == null) player.clearVideoSurface() else player.setVideoTextureView(view)
    }

    fun load(url: String, playing: Boolean) {
        if (url == this.url) { setPlaying(playing); return }
        this.url = url
        _hasFrame.value = false
        _failed.value = false
        _aspect.value = 0f
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = playing
        player.prepare()
    }

    fun setPlaying(playing: Boolean) {
        if (player.playWhenReady != playing) player.playWhenReady = playing
    }

    fun release() = player.release()
}
