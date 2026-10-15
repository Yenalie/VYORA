package com.vyora.music.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sleep timer shared by the UI and the playback service (same process). The UI sets it;
 * [com.vyora.music.playback.service.PlaybackService] watches it, fades the music out over the last
 * [FADE_MS] and pauses. Kept in memory only: a timer should not survive a reboot.
 */
object SleepTimer {
    sealed interface Mode {
        /** Pause at [endsAtElapsed] (SystemClock.elapsedRealtime based). */
        data class At(val endsAtElapsed: Long, val minutes: Int) : Mode
        /** Pause when the current song finishes. */
        data object EndOfTrack : Mode
    }

    const val FADE_MS = 10_000L
    val PRESETS = listOf(5, 15, 30, 45, 60, 90)

    private val _mode = MutableStateFlow<Mode?>(null)
    val mode: StateFlow<Mode?> = _mode.asStateFlow()

    fun start(minutes: Int) {
        _mode.value = Mode.At(SystemClock.elapsedRealtime() + minutes * 60_000L, minutes)
    }

    fun endOfTrack() {
        _mode.value = Mode.EndOfTrack
    }

    fun cancel() {
        _mode.value = null
    }

    /** Milliseconds left for a timed mode, null otherwise. */
    fun remainingMs(now: Long = SystemClock.elapsedRealtime()): Long? =
        (_mode.value as? Mode.At)?.let { (it.endsAtElapsed - now).coerceAtLeast(0L) }
}
