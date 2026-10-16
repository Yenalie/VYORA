package com.vyora.music.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.vyora.music.data.local.AudioFormatInfo
import com.vyora.music.data.youtube.StreamResolver
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.playback.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object PlayerManager {
    private const val TAG = "VYORA-Player"

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playerListener: Player.Listener? = null
    private var appContext: Context? = null
    private var scope: CoroutineScope = newScope()
    private var progressJob: Job? = null
    private var controllerConnectionId = 0L

    private var pendingQueue: PendingQueue? = null

    private class PendingQueue(val items: List<SimpleItem>, val startIndex: Int, val shuffle: Boolean?)

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()
    private val _currentTitle = MutableStateFlow<String?>(null)
    val currentTitle: StateFlow<String?> = _currentTitle.asStateFlow()
    private val _currentArtist = MutableStateFlow<String?>(null)
    val currentArtist: StateFlow<String?> = _currentArtist.asStateFlow()
    private val _currentThumbnail = MutableStateFlow<String?>(null)
    val currentThumbnail: StateFlow<String?> = _currentThumbnail.asStateFlow()
    private val _currentItem = MutableStateFlow<SimpleItem?>(null)
    val currentItem: StateFlow<SimpleItem?> = _currentItem.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    private val _upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
    val upNext: StateFlow<List<QueueEntry>> = _upNext.asStateFlow()
    private val _audioFormat = MutableStateFlow<AudioFormatInfo?>(null)
    /** What is being decoded right now: codec, resolution and, for streams, the YouTube bitrate. */
    val audioFormat: StateFlow<AudioFormatInfo?> = _audioFormat.asStateFlow()
    private val _playbackError = MutableStateFlow<Int?>(null)
    val playbackError: StateFlow<Int?> = _playbackError.asStateFlow()

    fun initialize(context: Context) {
        if (!scope.coroutineContext.isActive) scope = newScope()
        val applicationContext = context.applicationContext
        appContext = applicationContext
        LibraryManager.init(applicationContext)
        connectController()
    }

    private fun connectController() {
        val ctx = appContext ?: return
        if (mediaController != null || controllerFuture != null) return

        val connectionId = ++controllerConnectionId
        val future = MediaController.Builder(
            ctx,
            SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        ).buildAsync()
        controllerFuture = future

        future.addListener({
            try {
                val controller = future.get()
                if (connectionId != controllerConnectionId || controllerFuture !== future) {
                    controller.release()
                    return@addListener
                }

                mediaController = controller
                val listener = buildListener(controller)
                playerListener = listener
                controller.addListener(listener)
                Log.d(TAG, "MediaController connected")

                syncFromController(controller)

                pendingQueue?.let { pending ->
                    pendingQueue = null
                    applyQueue(controller, pending.items, pending.startIndex, pending.shuffle)
                }
            } catch (e: Exception) {
                if (controllerFuture === future) controllerFuture = null
                Log.e(TAG, "Failed to connect MediaController", e)
                _playbackError.value = R.string.error_player_not_ready
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun buildListener(controller: MediaController) = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) startProgressUpdates() else stopProgressUpdates()
        }

        override fun onPlaybackStateChanged(state: Int) {
            _isBuffering.value = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> {
                    val d = controller.duration
                    if (d != C.TIME_UNSET && d > 0L) _durationMs.value = d
                }
                Player.STATE_ENDED -> {
                    _isPlaying.value = false
                    _positionMs.value = 0L
                    _progress.value = 0f
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            publishCurrent(mediaItem)
            publishUpNext(controller)
            if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                mediaItem.toSimpleItem()?.let { LibraryManager.addToRecent(it) }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _audioFormat.value = audioFormatOf(tracks, controller.currentMediaItem?.mediaId)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            publishUpNext(controller)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffle.value = shuffleModeEnabled
            publishUpNext(controller)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            _playbackError.value = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> R.string.error_offline
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> return
                else -> R.string.error_cannot_play
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun audioFormatOf(tracks: Tracks, mediaId: String?): AudioFormatInfo? {
        val format = tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected).map(group::getTrackFormat) }
            .firstOrNull() ?: return null
        val mime = format.sampleMimeType ?: format.containerMimeType ?: return null
        val stream = mediaId?.takeUnless(SimpleItem::isLocalId)?.let(StreamResolver::pickedAudio)
        val bits = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        return AudioFormatInfo(
            mimeType = mime,
            sampleRateHz = format.sampleRate.coerceAtLeast(0),
            bitDepth = bits,
            // NewPipe reports average bitrate in kbps but itag bitrate in bps.
            bitrate = stream?.bitrate?.takeIf { it > 0 }?.let { if (it < 10_000) it * 1000 else it }
                ?: maxOf(format.averageBitrate, format.bitrate, 0),
            channels = format.channelCount.coerceAtLeast(0),
        )
    }

    private fun syncFromController(controller: MediaController) {
        _audioFormat.value = audioFormatOf(controller.currentTracks, controller.currentMediaItem?.mediaId)
        _isPlaying.value = controller.isPlaying
        _isBuffering.value = controller.playbackState == Player.STATE_BUFFERING
        _shuffle.value = controller.shuffleModeEnabled
        _repeatMode.value = controller.repeatMode
        publishCurrent(controller.currentMediaItem)
        publishUpNext(controller)
        val d = controller.duration
        if (d != C.TIME_UNSET && d > 0L) _durationMs.value = d
        _positionMs.value = controller.currentPosition.coerceAtLeast(0L)
        val known = _durationMs.value
        if (known > 0L) _progress.value = (_positionMs.value.toFloat() / known).coerceIn(0f, 1f)
        if (controller.isPlaying) startProgressUpdates()
    }

    private fun publishCurrent(mediaItem: MediaItem?) {
        val item = mediaItem?.toSimpleItem()
        _currentItem.value = item
        _currentTitle.value = item?.title
        _currentArtist.value = item?.artist
        _currentThumbnail.value = item?.thumbnail
        _positionMs.value = 0L
        _progress.value = 0f
        _durationMs.value = (item?.duration ?: 0L) * 1000L
    }

    fun setQueue(items: List<SimpleItem>, startIndex: Int = 0, shuffle: Boolean? = null) {
        _playbackError.value = null
        val controller = mediaController
        if (controller == null) {
            pendingQueue = PendingQueue(items, startIndex, shuffle)
            connectController()
            return
        }
        applyQueue(controller, items, startIndex, shuffle)
    }

    private fun applyQueue(controller: MediaController, items: List<SimpleItem>, startIndex: Int, shuffle: Boolean?) {
        if (items.isEmpty()) {
            controller.stop()
            controller.clearMediaItems()
            publishCurrent(null)
            return
        }
        val start = startIndex.coerceIn(0, items.lastIndex)
        if (shuffle != null) controller.shuffleModeEnabled = shuffle
        controller.setMediaItems(items.map { it.toMediaItem() }, start, 0L)
        controller.prepare()
        controller.play()
    }

    private fun publishUpNext(controller: MediaController) {
        val timeline = controller.currentTimeline
        if (timeline.isEmpty || controller.currentMediaItemIndex == C.INDEX_UNSET) {
            _upNext.value = emptyList()
            return
        }
        val shuffle = controller.shuffleModeEnabled
        val result = mutableListOf<QueueEntry>()
        var index = timeline.getNextWindowIndex(controller.currentMediaItemIndex, Player.REPEAT_MODE_OFF, shuffle)
        while (index != C.INDEX_UNSET && result.size < 200) {
            val media = controller.getMediaItemAt(index)
            media.toSimpleItem()?.let { result += QueueEntry(index, it, MediaItems.isAutoplay(media)) }
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        _upNext.value = result
    }

    fun playNext(item: SimpleItem) {
        val controller = mediaController
        if (controller == null || controller.mediaItemCount == 0) {
            setQueue(listOf(item), 0)
            return
        }
        controller.addMediaItem(controller.currentMediaItemIndex + 1, item.toMediaItem())
    }

    fun addToQueue(item: SimpleItem) {
        val controller = mediaController
        if (controller == null || controller.mediaItemCount == 0) {
            setQueue(listOf(item), 0)
            return
        }
        val current = controller.currentMediaItemIndex
        val firstAuto = (current + 1 until controller.mediaItemCount)
            .firstOrNull { MediaItems.isAutoplay(controller.getMediaItemAt(it)) }
        if (firstAuto != null) controller.addMediaItem(firstAuto, item.toMediaItem())
        else controller.addMediaItem(item.toMediaItem())
    }

    fun clearUpNext() {
        val controller = mediaController ?: return
        val current = controller.currentMediaItemIndex
        for (i in controller.mediaItemCount - 1 downTo current + 1) {
            if (!MediaItems.isAutoplay(controller.getMediaItemAt(i))) controller.removeMediaItem(i)
        }
    }

    fun startRadio(item: SimpleItem) {
        if (item.isLocal) return
        setQueue(listOf(item), 0, shuffle = false)
        scope.launch {
            val songs = YouTubeClient.radio(item.id).getOrNull().orEmpty()
            val controller = mediaController ?: return@launch
            if (controller.currentMediaItem?.mediaId != item.id || songs.isEmpty()) return@launch
            for (i in controller.mediaItemCount - 1 downTo controller.currentMediaItemIndex + 1) {
                if (MediaItems.isAutoplay(controller.getMediaItemAt(i))) controller.removeMediaItem(i)
            }
            controller.addMediaItems(songs.take(RADIO_SIZE).map { it.toMediaItem() })
        }
    }

    private const val RADIO_SIZE = 25

    fun playQueueEntry(entry: QueueEntry) {
        val controller = mediaController ?: return
        if (entry.index !in 0 until controller.mediaItemCount) return
        prepareIfIdle(controller)
        controller.seekTo(entry.index, 0L)
        controller.play()
    }

    fun removeQueueEntry(entry: QueueEntry) {
        val controller = mediaController ?: return
        if (entry.index !in 0 until controller.mediaItemCount) return
        if (entry.index == controller.currentMediaItemIndex) return
        controller.removeMediaItem(entry.index)
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    fun togglePlayPause() {
        val controller = mediaController
        if (controller == null) {
            connectController()
            return
        }
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0, 0L)
            else if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) controller.prepare()
            controller.play()
        }
    }

    private fun prepareIfIdle(controller: MediaController) {
        if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) controller.prepare()
    }

    fun next() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return
        prepareIfIdle(controller)
        if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
        else {
            val first = controller.currentTimeline.getFirstWindowIndex(controller.shuffleModeEnabled)
            controller.seekTo(if (first == C.INDEX_UNSET) 0 else first, 0L)
        }
        controller.play()
    }

    fun previous() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return
        prepareIfIdle(controller)
        controller.seekToPrevious()
        controller.play()
    }

    fun toggleShuffle() {
        mediaController?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        mediaController?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun currentPositionMs(): Long = mediaController?.currentPosition?.coerceAtLeast(0L) ?: _positionMs.value

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        val target = positionMs.coerceAtLeast(0L)
        controller.seekTo(target)
        _positionMs.value = target
        val duration = _durationMs.value
        if (duration > 0L) _progress.value = (target.toFloat() / duration).coerceIn(0f, 1f)
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive) {
                mediaController?.let { controller ->
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val duration = controller.duration.takeIf { it > 0L && it != C.TIME_UNSET }
                        ?: _durationMs.value.coerceAtLeast(1L)
                    _positionMs.value = pos
                    _durationMs.value = duration
                    _progress.value = (pos.toFloat() / duration).coerceIn(0f, 1f)
                }
                delay(500L)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        ++controllerConnectionId
        pendingQueue = null
        stopProgressUpdates()
        playerListener?.let { listener -> mediaController?.removeListener(listener) }
        playerListener = null
        mediaController?.release()
        mediaController = null
        controllerFuture?.cancel(true)
        controllerFuture = null
        scope.cancel()
        scope = newScope()
        _isPlaying.value = false
    }

    private fun SimpleItem.toMediaItem(): MediaItem = MediaItems.from(this)

    private fun MediaItem.toSimpleItem(): SimpleItem? = MediaItems.toSimpleItem(this)
}

data class QueueEntry(val index: Int, val item: SimpleItem, val autoplay: Boolean = false)
