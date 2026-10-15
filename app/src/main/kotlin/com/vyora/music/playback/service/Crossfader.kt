package com.vyora.music.playback.service

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real overlapping crossfade, like Apple Music's Crossfade setting.
 *
 * ExoPlayer plays one playlist item at a time, so a second, silent "tail" player is used:
 *  1. A few seconds before the fade window, the tail loads the CURRENT song at the point
 *     where the fade will start, paused (stream URL comes from the same cache, so this is
 *     a quick range request).
 *  2. When the main player reaches that point, the tail starts playing from exactly there and
 *     the main player jumps to the next song. Once the next song is actually playing, the
 *     tail fades out while main fades in (equal power), so buffering never causes a dip.
 *  3. When the window is over the tail stops.
 *
 * The session, notification and queue only ever see the main player; the tail is invisible
 * to the rest of the app. It shares the main audio session, so the equalizer applies to both.
 */
@UnstableApi
class Crossfader(
    private val context: Context,
    private val main: ExoPlayer,
    private val sourceFactory: MediaSource.Factory,
) {
    private enum class Phase { IDLE, PREPARING, FADING }

    private var tail: ExoPlayer? = null
    private var phase = Phase.IDLE
    private var handoffAtMs = 0L        // main position where the tail takes over
    private var windowMs = 0L
    private var preparedIndex = C.INDEX_UNSET
    private var fadeStartElapsed = 0L
    /** Next song's fade-in: starts once the main player is actually playing it. */
    private var fadeInPending = false
    private var fadeInStartElapsed = 0L
    /** Index the main player jumps to at handoff; any other transition cancels the fade. */
    var expectedIndex = C.INDEX_UNSET
        private set

    /** Tail playing/loading, or the new song still fading in. */
    val isActive get() = phase != Phase.IDLE || fadeInPending || fadeInStartElapsed > 0L

    /**
     * Called every tick (~50 ms) while the main player plays. Returns the volume factor for
     * the main player (fade-in after a handoff); [outer] (sleep-timer fade) multiplies both.
     */
    fun tick(enabled: Boolean, seconds: Float, outer: Float): Float {
        val now = SystemClock.elapsedRealtime()
        // The new song's fade-in starts once the main player is really playing it.
        if (fadeInPending && main.isPlaying) {
            fadeInPending = false
            fadeInStartElapsed = now
        }
        when (phase) {
            Phase.IDLE -> if (enabled) maybePrepare(seconds)
            Phase.PREPARING -> {
                val pos = main.currentPosition
                val t = tail
                when {
                    !enabled || t == null || main.currentMediaItemIndex != preparedIndex || pos < handoffAtMs - PREPARE_LEAD_MS - 1500 -> cancel()
                    pos >= handoffAtMs && t.playbackState == Player.STATE_READY -> handoff(now)
                    pos >= handoffAtMs + GIVE_UP_MS -> cancel() // tail never got ready: plain transition
                }
            }
            Phase.FADING -> {
                // The old song stays at full volume until the new one is audible (or the
                // wait gets too long), so a slow network never leaves a dip in between.
                val start = when {
                    fadeInStartElapsed > 0L -> fadeInStartElapsed
                    now - fadeStartElapsed >= MAX_FADE_IN_WAIT_MS -> fadeStartElapsed + MAX_FADE_IN_WAIT_MS
                    else -> 0L
                }
                val f = if (start == 0L) 0f else ((now - start).toFloat() / windowMs).coerceIn(0f, 1f)
                tail?.volume = cos(f * PI / 2).toFloat() * outer
                if (f >= 1f || tail?.playbackState == Player.STATE_ENDED) stopTail()
            }
        }
        if (fadeInStartElapsed > 0L) {
            val f = ((now - fadeInStartElapsed).toFloat() / windowMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            if (f >= 1f) fadeInStartElapsed = 0L
            return sin(f * PI / 2).toFloat()
        }
        return if (fadeInPending) 0f else 1f
    }

    private fun maybePrepare(seconds: Float) {
        if (main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem()) return
        if (main.playbackState != Player.STATE_READY) return
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val window = (seconds * 1000).toLong().coerceIn(1000L, 12_000L)
        // Short clips or a seek into the last seconds: no room for a fade.
        if (duration < window * 3) return
        val handoff = duration - window
        val pos = main.currentPosition
        if (pos < handoff - PREPARE_LEAD_MS || pos > handoff - 500) return

        val item: MediaItem = main.currentMediaItem ?: return
        val t = tail ?: buildTail().also { tail = it }
        t.setMediaSource(sourceFactory.createMediaSource(item), handoff)
        t.volume = 0f
        t.playWhenReady = false
        t.prepare()
        phase = Phase.PREPARING
        handoffAtMs = handoff
        windowMs = window
        preparedIndex = main.currentMediaItemIndex
    }

    private fun handoff(now: Long) {
        val t = tail ?: return cancel()
        // Line the tail up with where main is now (a few ms past the handoff point).
        // Small drift is inaudible; a seek would cost the tail a short rebuffer instead.
        val drift = main.currentPosition - handoffAtMs
        if (drift > MAX_DRIFT_MS) t.seekTo(handoffAtMs + drift)
        t.volume = main.volume
        t.play()
        expectedIndex = main.nextMediaItemIndex
        main.volume = 0f
        fadeInPending = true
        fadeInStartElapsed = 0L
        // Set before the seek: ExoPlayer reports the transition from inside seekToNext...,
        // and onTransition() must already see this as our own handoff.
        phase = Phase.FADING
        fadeStartElapsed = now
        main.seekToNextMediaItem()
    }

    /** User skipped, paused, seeked or changed the queue: drop the tail at once. */
    fun cancel() {
        if (phase == Phase.IDLE && !fadeInPending && fadeInStartElapsed == 0L) return
        stopTail()
        fadeInPending = false
        fadeInStartElapsed = 0L
    }

    /** The main player moved to [index]; true when that was our own handoff. */
    fun onTransition(index: Int): Boolean {
        val ours = index == expectedIndex && phase == Phase.FADING
        expectedIndex = C.INDEX_UNSET
        if (!ours) cancel()
        return ours
    }

    private fun stopTail() {
        tail?.let {
            it.stop()
            it.clearMediaItems()
        }
        phase = Phase.IDLE
        preparedIndex = C.INDEX_UNSET
        expectedIndex = C.INDEX_UNSET
    }

    private fun buildTail(): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(sourceFactory)
            // The main player owns audio focus; the tail must never steal or duck it.
            .setAudioAttributes(attrs, false)
            .build()
            .also { it.setAudioSessionId(main.audioSessionId) }
    }

    fun onAudioSessionChanged(id: Int) {
        tail?.setAudioSessionId(id)
    }

    fun release() {
        tail?.release()
        tail = null
        phase = Phase.IDLE
    }

    private companion object {
        const val PREPARE_LEAD_MS = 4_000L
        const val GIVE_UP_MS = 1_500L
        const val MAX_DRIFT_MS = 150L
        /** Longest the old song holds full volume waiting for the new one to start. */
        const val MAX_FADE_IN_WAIT_MS = 4_000L
    }
}
