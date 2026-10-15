package com.vyora.music.playback.service

import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.framework.CastContext
import com.vyora.music.R
import com.vyora.music.data.discord.DiscordPresence
import com.vyora.music.data.discord.PresenceTrack
import com.vyora.music.playback.cast.CastItemConverter
import com.vyora.music.playback.cast.CastProxy
import com.vyora.music.playback.cast.CastSupport
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
    private val autoplayed = LinkedHashSet<String>()
    private var autoplayFailedSeed: String? = null
    private var castPlayer: CastPlayer? = null
    private var castSnapshot: QueueSnapshot? = null
    private var castPollJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val active: Player get() = mediaSession?.player ?: player

    private class QueueSnapshot(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val repeatMode: Int,
    )

    private val presenceListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                )
            ) pushPresence(player)
        }
    }

    private val castListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player === active) rememberCast(player)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = maybeAutoplay()

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) maybeAutoplay()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = holdCastLocks(playWhenReady)

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Cast playback error (${error.errorCodeName})", error)
        }
    }

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
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioEffects.attach(audioSessionId)
                crossfader.onAudioSessionChanged(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                retriedIds.clear()
                if (!crossfader.onTransition(player.currentMediaItemIndex)) player.volume = sleepFactor()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) keepCurrentFirstInShuffle()
                saveQueue()
                maybeAutoplay()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) startTicker() else crossfader.cancel()
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                    SleepTimer.mode.value == SleepTimer.Mode.EndOfTrack
                ) finishSleep(pause = false)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
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
                if (shuffleModeEnabled) keepCurrentFirstInShuffle()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveQueue()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    saveQueue()
                    maybeAutoplay()
                }
            }
        })
        player.addListener(presenceListener)
        crossfader = Crossfader(this, player, sourceFactory)

        player.setShuffleOrder(PlayNextShuffleOrder(IntArray(0)))
        restoreQueue()

        AudioEffects.attach(player.audioSessionId)

        settingsJob = scope.launch {
            var autoplayWas: Boolean? = null
            var discordWas: Boolean? = null
            SettingsRepository.settings.collect { s ->
                if (discordWas != s.discordPresence) {
                    if (s.discordPresence) pushPresence(active) else DiscordPresence.stop()
                    discordWas = s.discordPresence
                }
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
        setupCast()
    }

    private fun setupCast() {
        if (!CastSupport.hasPlayServices(this)) return
        val task = runCatching { CastContext.getSharedInstance(this, ContextCompat.getMainExecutor(this)) }
            .onFailure { Log.w(TAG, "Cast framework unavailable", it) }
            .getOrNull() ?: return
        task.addOnSuccessListener { cast ->
            if (mediaSession == null) return@addOnSuccessListener
            val cp = CastPlayer(cast, CastItemConverter { id -> CastProxy.urlFor(id) })
            cp.addListener(presenceListener)
            cp.addListener(castListener)
            cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = switchTo(cp)
                override fun onCastSessionUnavailable() = switchTo(player)
            })
            castPlayer = cp
            CastSupport.publish(true, null)
            if (cp.isCastSessionAvailable) switchTo(cp)
        }.addOnFailureListener { Log.w(TAG, "Cast framework unavailable", it) }
    }

    private fun switchTo(target: Player) {
        val session = mediaSession ?: return
        val previous = session.player
        if (previous === target) return
        val toCast = target !== player
        val snapshot = if (toCast) snapshotOf(previous) else castSnapshot ?: snapshotOf(previous)
        if (toCast) {
            runCatching { CastProxy.start(this) }.onFailure { Log.e(TAG, "Cast proxy failed", it) }
            crossfader.cancel()
            player.pause()
            player.volume = 1f
        } else {
            castPollJob?.cancel()
            castPollJob = null
            holdCastLocks(false)
            runCatching { previous.stop() }
            CastProxy.stop()
        }
        session.player = target
        if (snapshot.items.isNotEmpty()) {
            target.setMediaItems(snapshot.items, snapshot.index.coerceIn(0, snapshot.items.lastIndex), snapshot.positionMs)
            target.repeatMode = snapshot.repeatMode
            target.playWhenReady = snapshot.playWhenReady
            target.prepare()
        }
        castSnapshot = null
        if (toCast) {
            rememberCast(target)
            holdCastLocks(snapshot.playWhenReady)
            castPollJob = scope.launch {
                while (isActive) {
                    delay(2_000)
                    if (active === target) rememberCast(target)
                }
            }
        } else {
            saveQueue()
        }
        val device = if (toCast) {
            runCatching { CastSupport.context(this)?.sessionManager?.currentCastSession?.castDevice?.friendlyName }.getOrNull()
                ?: getString(R.string.cast_device_fallback)
        } else null
        CastSupport.publish(true, device)
        pushPresence(target)
    }

    private fun snapshotOf(p: Player): QueueSnapshot = QueueSnapshot(
        items = List(p.mediaItemCount) { p.getMediaItemAt(it) },
        index = p.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = p.currentPosition.coerceAtLeast(0L),
        playWhenReady = p.playWhenReady,
        repeatMode = p.repeatMode,
    )

    private fun rememberCast(p: Player) {
        if (p.mediaItemCount == 0) return
        castSnapshot = snapshotOf(p)
    }

    @Suppress("DEPRECATION")
    private fun holdCastLocks(hold: Boolean) {
        val casting = active !== player
        if (hold && casting) {
            if (wakeLock == null) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VYORA:cast")
                    .apply { setReferenceCounted(false) }
            }
            if (wifiLock == null) {
                wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VYORA:cast")
                    .apply { setReferenceCounted(false) }
            }
            runCatching { wakeLock?.acquire(4 * 60 * 60 * 1000L) }
            runCatching { wifiLock?.acquire() }
        } else {
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        }
    }

    private fun pushPresence(p: Player) {
        if (p !== active) return
        val item = p.currentMediaItem
        val playing = p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        if (item == null || !playing) {
            DiscordPresence.update(null)
            return
        }
        val md = item.mediaMetadata
        val now = System.currentTimeMillis()
        val started = now - p.currentPosition.coerceAtLeast(0L)
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: (md.extras?.getLong(MediaItems.EXTRA_DURATION_SEC, 0L) ?: 0L) * 1000L
        DiscordPresence.update(
            PresenceTrack(
                title = md.title?.toString().orEmpty().ifBlank { item.mediaId },
                artist = md.artist?.toString().orEmpty(),
                startedAt = started,
                endsAt = if (duration > 0) started + duration else 0L,
            )
        )
    }

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

    private fun tick(): Boolean {
        val mode = SleepTimer.mode.value
        if (mode is SleepTimer.Mode.At && SleepTimer.remainingMs() == 0L) {
            finishSleep(pause = true)
            return false
        }
        if (!player.playWhenReady && !crossfader.isActive) return mode != null
        val s = SettingsRepository.current
        val outer = sleepFactor()
        val fade = crossfader.tick(s.crossfade && mode != SleepTimer.Mode.EndOfTrack, s.crossfadeSeconds, outer)
        player.volume = fade * outer
        return s.crossfade || mode != null || crossfader.isActive
    }

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
        if (pause) active.pause()
        SleepTimer.cancel()
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
        if (!pause && player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    private fun maybeAutoplay() {
        val player = active
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
                autoplayFailedSeed = seed
                return@launch
            }
            autoplayFailedSeed = null
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
                player.seekTo(firstNew, 0L)
                player.prepare()
                player.play()
            }
        }
    }

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

    private fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val saved = QueueStore.load(this) ?: return
        player.setMediaItems(saved.items, saved.index, saved.positionMs)
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
        DiscordPresence.stop()
        holdCastLocks(false)
        CastProxy.stop()
        castPlayer?.let {
            it.setSessionAvailabilityListener(null)
            it.removeListener(castListener)
            it.removeListener(presenceListener)
            it.release()
        }
        castPlayer = null
        CastSupport.publish(false, null)
        mediaSession?.run {
            release()
            mediaSession = null
        }
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VYORA-Service"
        private const val AUTOPLAY_WHEN_LEFT = 2
        private const val AUTOPLAY_BATCH = 15
    }
}
