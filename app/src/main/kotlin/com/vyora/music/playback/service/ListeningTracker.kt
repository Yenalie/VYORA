package com.vyora.music.playback.service

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.playback.MediaItems

/**
 * Measures how long each song actually played (paused, buffering and seeking don't count) and reports it
 * once the song is left. Attached to the local ExoPlayer in [PlaybackService], so it works with the UI closed.
 */
class ListeningTracker(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val onListen: (SimpleItem, Long) -> Unit,
) : Player.Listener {

    private var player: Player? = null
    private var item: SimpleItem? = null
    private var durationMs: Long = 0L
    private var accumulatedMs: Long = 0L
    private var playingSince: Long = -1L

    fun attach(p: Player) {
        detach()
        player = p
        p.addListener(this)
        begin(p.currentMediaItem, p)
        if (p.isPlaying) playingSince = clock()
    }

    fun detach() {
        flush()
        player?.removeListener(this)
        player = null
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            if (playingSince < 0) playingSince = clock()
        } else {
            pause()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        flush()
        val p = player ?: return
        begin(mediaItem, p)
        if (p.isPlaying) playingSince = clock()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val p = player ?: return
        if (durationMs <= 0L) p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { durationMs = it }
        if (playbackState == Player.STATE_ENDED) flush()
    }

    private fun begin(mediaItem: MediaItem?, p: Player) {
        item = mediaItem?.let(MediaItems::toSimpleItem)
        accumulatedMs = 0L
        playingSince = -1L
        durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((item?.duration ?: 0L) * 1000L)
    }

    private fun pause() {
        if (playingSince >= 0) {
            accumulatedMs += (clock() - playingSince).coerceAtLeast(0L)
            playingSince = -1L
        }
    }

    private fun flush() {
        pause()
        val current = item
        val listened = accumulatedMs
        accumulatedMs = 0L
        if (current != null && counts(listened, durationMs)) onListen(current, listened)
        // A second flush for the same song (e.g. ENDED then transition) must not double count.
        item = if (current != null && counts(listened, durationMs)) null else current
    }

    companion object {
        const val MIN_LISTEN_MS = 30_000L
        const val SHORT_TRACK_MIN_MS = 10_000L

        /** Like scrobbling: 30 s, or half of a short song (but at least 10 s). */
        fun counts(listenedMs: Long, durationMs: Long): Boolean {
            if (listenedMs >= MIN_LISTEN_MS) return true
            if (durationMs in 1 until MIN_LISTEN_MS * 2) return listenedMs >= maxOf(SHORT_TRACK_MIN_MS, durationMs / 2)
            return false
        }
    }
}
