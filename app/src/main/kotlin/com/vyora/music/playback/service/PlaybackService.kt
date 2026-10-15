package com.vyora.music.playback.service

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vyora.music.MainActivity
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.youtube.StreamResolver
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.playback.MediaItems
import com.vyora.music.playback.SleepTimer
import com.vyora.music.playback.audio.AudioEffects
import com.vyora.music.playback.source.MediaCache
import com.vyora.music.playback.source.YouTubeDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer, the playlist and stream-URL resolution.
 *
 * Every queue entry is a MediaItem whose URI is the YouTube watch URL. The real
 * audio URL is resolved lazily by [ResolvingDataSource] right before ExoPlayer
 * loads that item. Because this all lives in the service, next/previous from the
 * notification, lockscreen or a headset work, and auto-advance keeps working
 * after the Activity is gone.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private var settingsJob: Job? = null
    private val retriedIds = mutableSetOf<String>()
    private lateinit var crossfader: Crossfader
    private var tickerJob: Job? = null
    private var autoplayJob: Job? = null
    /** Songs Autoplay already added this session, so the radio doesn't loop the same ones. */
    private val autoplayed = LinkedHashSet<String>()
    private var autoplayFailedSeed: String? = null

    override fun onCreate() {
        super.onCreate()
        SettingsRepository.init(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val sourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(YouTubeDataSource.audioFactory(this))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(SettingsRepository.current.pauseOnDisconnect)
            // Keeps CPU + Wi-Fi awake so streaming doesn't stall with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioEffects.attach(audioSessionId)
                crossfader.onAudioSessionChanged(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // A new track gets a fresh chance at the one-shot 403 retry.
                retriedIds.clear()
                // Our own crossfade handoff keeps the fade going; anything else (skip, tap in
                // the queue, new list) cuts the old song's tail right away.
                if (!crossfader.onTransition(player.currentMediaItemIndex)) player.volume = sleepFactor()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) keepCurrentFirstInShuffle()
                saveQueue()
                maybeAutoplay()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) startTicker() else crossfader.cancel()
                // "End of song" sleep timer: ExoPlayer paused right at the end of the item.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                    SleepTimer.mode.value == SleepTimer.Mode.EndOfTrack
                ) finishSleep(pause = false)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // A seek inside the song during a fade: stop blending, play it at full volume.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                    crossfader.cancel()
                    player.volume = sleepFactor()
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) = maybeAutoplay()

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) maybeAutoplay()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // Turning shuffle on keeps the current song and shuffles only what follows.
                if (shuffleModeEnabled) keepCurrentFirstInShuffle()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Remember where playback stopped, so the next launch resumes there.
                if (!isPlaying) saveQueue()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    saveQueue()
                    maybeAutoplay()
                }
            }
        })
        crossfader = Crossfader(this, player, sourceFactory)

        // Apple Music-like queue edits under shuffle ("Play Next" really plays next).
        player.setShuffleOrder(PlayNextShuffleOrder(IntArray(0)))
        restoreQueue()

        // Equalizer / bass boost / surround / loudness follow the Settings page.
        AudioEffects.attach(player.audioSessionId)

        // Playback preferences apply live, without restarting the service.
        settingsJob = scope.launch {
            var autoplayWas: Boolean? = null
            SettingsRepository.settings.collect { s ->
                player.skipSilenceEnabled = s.skipSilence
                MediaCache.setMaxBytes(s.mediaCache.bytes)
                player.setHandleAudioBecomingNoisy(s.pauseOnDisconnect)
                if (s.crossfade && player.playWhenReady) startTicker()
                if (autoplayWas != null && autoplayWas != s.autoplay) {
                    if (s.autoplay) maybeAutoplay() else removeUpcomingAutoplay()
                }
                autoplayWas = s.autoplay
            }
        }
        scope.launch {
            SleepTimer.mode.collect { mode ->
                // "End of song" uses ExoPlayer's own pause-at-end; timed modes fade + pause.
                player.pauseAtEndOfMediaItems = mode == SleepTimer.Mode.EndOfTrack
                if (mode == null) player.volume = 1f
                if (mode != null) startTicker()
            }
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    /**
     * - HTTP 403/410: the cached stream URL expired or is bound to another IP. Drop it and
     *   retry the same track once, from the same position.
     * - No connection / timeout: stay on the track (paused in error) instead of skipping
     *   through the whole queue while offline. Pressing play retries.
     * - Anything else (removed, region-locked...): skip to the next track if enabled.
     */
    private fun handleError(error: PlaybackException) {
        val item = player.currentMediaItem
        val id = item?.mediaId.orEmpty()
        Log.e(TAG, "Playback error on item $id (${error.errorCodeName})", error)

        val http = generateSequence(error as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        if (http != null && (http.responseCode == 403 || http.responseCode == 410) && id !in retriedIds) {
            retriedIds += id
            (YouTubeDataSource.videoIdOf(item?.localConfiguration?.uri) ?: id).let(StreamResolver::invalidate)
            player.seekTo(player.currentMediaItemIndex, player.currentPosition)
            player.prepare()
            player.play()
            return
        }

        val offline = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (offline) return

        if (SettingsRepository.current.skipUnavailable && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    // ------------------------------------------------------------ volume: fades & sleep

    /** One loop for crossfade and the sleep timer: 50 ms while something fades, else 250 ms. */
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val busy = tick()
                if (!busy) break
                delay(if (crossfader.isActive || sleepFactor() < 1f) 50L else 250L)
            }
            tickerJob = null
        }
    }

    /** Returns false when there is nothing left to watch (paused, no timer). */
    private fun tick(): Boolean {
        val mode = SleepTimer.mode.value
        if (mode is SleepTimer.Mode.At && SleepTimer.remainingMs() == 0L) {
            finishSleep(pause = true)
            return false
        }
        if (!player.playWhenReady && !crossfader.isActive) return mode != null
        val s = SettingsRepository.current
        val outer = sleepFactor()
        // No crossfade into a song that the "end of song" timer will never play.
        val fade = crossfader.tick(s.crossfade && mode != SleepTimer.Mode.EndOfTrack, s.crossfadeSeconds, outer)
        player.volume = fade * outer
        // Nothing to watch with crossfade off and no timer: stop polling until that changes.
        return s.crossfade || mode != null || crossfader.isActive
    }

    /** Sleep-timer fade over its last [SleepTimer.FADE_MS] (1 = full volume). */
    private fun sleepFactor(): Float = when (val mode = SleepTimer.mode.value) {
        is SleepTimer.Mode.At -> ((SleepTimer.remainingMs() ?: 0L).toFloat() / SleepTimer.FADE_MS).coerceIn(0f, 1f)
        SleepTimer.Mode.EndOfTrack -> {
            val d = player.duration
            if (d == C.TIME_UNSET || d < SleepTimer.FADE_MS * 2) 1f
            else ((d - player.currentPosition).toFloat() / SleepTimer.FADE_MS).coerceIn(0f, 1f)
        }
        null -> 1f
    }

    private fun finishSleep(pause: Boolean) {
        crossfader.cancel()
        if (pause) player.pause()
        SleepTimer.cancel()
        player.pauseAtEndOfMediaItems = false
        // Paused already, so restoring the volume is silent; next play starts normally.
        player.volume = 1f
        // The "end of song" pause happens at the end: start the next song from 0 next time.
        if (!pause && player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    // --------------------------------------------------------------------- autoplay

    /**
     * Apple Music's ∞: when two or fewer songs are left, append ~15 similar ones from the
     * YouTube Music radio of the last song. Runs in the service, so it also works with the
     * screen off. Repeat (all / one) means the user wants this queue, so nothing is added.
     */
    private fun maybeAutoplay() {
        if (!SettingsRepository.current.autoplay) return
        if (player.repeatMode != Player.REPEAT_MODE_OFF || player.mediaItemCount == 0) return
        if (autoplayJob?.isActive == true) return
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        var left = 0
        var index = player.currentMediaItemIndex
        while (left <= AUTOPLAY_WHEN_LEFT) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            if (index == C.INDEX_UNSET) break
            left++
        }
        if (left > AUTOPLAY_WHEN_LEFT) return
        val lastIndex = timeline.getLastWindowIndex(shuffle).takeIf { it != C.INDEX_UNSET } ?: return
        val seed = player.getMediaItemAt(lastIndex).mediaId
        if (seed.isBlank() || seed == autoplayFailedSeed) return

        autoplayJob = scope.launch {
            val songs = YouTubeClient.radio(seed).getOrNull()
            if (songs.isNullOrEmpty()) {
                autoplayFailedSeed = seed // offline or no mix: don't retry on every event
                return@launch
            }
            autoplayFailedSeed = null
            // Re-check: the user may have turned it off or replaced the queue meanwhile.
            if (!SettingsRepository.current.autoplay || player.mediaItemCount == 0) return@launch
            val inQueue = HashSet<String>().apply { for (i in 0 until player.mediaItemCount) add(player.getMediaItemAt(i).mediaId) }
            val picks = songs.filter { it.id !in inQueue && it.id !in autoplayed }.take(AUTOPLAY_BATCH)
                .ifEmpty { songs.filter { it.id !in inQueue }.take(AUTOPLAY_BATCH) }
            if (picks.isEmpty()) {
                autoplayFailedSeed = seed
                return@launch
            }
            picks.forEach { autoplayed += it.id }
            while (autoplayed.size > 300) autoplayed.remove(autoplayed.first())
            val ended = player.playbackState == Player.STATE_ENDED
            val firstNew = player.mediaItemCount
            player.addMediaItems(picks.map { MediaItems.from(it, autoplay = true) })
            if (ended) {
                // The queue had already run out while we were fetching: carry on.
                player.seekTo(firstNew, 0L)
                player.prepare()
                player.play()
            }
        }
    }

    /** Autoplay switched off: drop the similar songs that haven't played yet. */
    private fun removeUpcomingAutoplay() {
        autoplayJob?.cancel()
        val current = player.currentMediaItemIndex
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i != current && i > current && MediaItems.isAutoplay(player.getMediaItemAt(i))) player.removeMediaItem(i)
        }
    }

    private fun keepCurrentFirstInShuffle() {
        if (!player.shuffleModeEnabled || player.mediaItemCount < 2) return
        val current = player.currentMediaItemIndex
        if (player.currentTimeline.getFirstWindowIndex(true) == current) return
        player.setShuffleOrder(PlayNextShuffleOrder.startingWith(current, player.mediaItemCount))
    }

    private fun saveQueue() {
        val count = player.mediaItemCount
        if (count == 0) {
            QueueStore.clear(this)
            return
        }
        val items = List(count) { player.getMediaItemAt(it) }
        QueueStore.save(this, items, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0L))
    }

    /** Puts the last session's queue back, paused and not yet buffering (no network). */
    private fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val saved = QueueStore.load(this) ?: return
        player.setMediaItems(saved.items, saved.index, saved.positionMs)
        // Not prepared on purpose: pressing play prepares it (see PlayerManager.togglePlayPause).
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        settingsJob?.cancel()
        crossfader.release()
        scope.cancel()
        AudioEffects.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VYORA-Service"
        private const val AUTOPLAY_WHEN_LEFT = 2
        private const val AUTOPLAY_BATCH = 15
    }
}
