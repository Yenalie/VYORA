package com.vyora.music.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SleepTimer {
    sealed interface Mode {
        data class At(val endsAtElapsed: Long, val minutes: Int) : Mode
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

    fun remainingMs(now: Long = SystemClock.elapsedRealtime()): Long? =
        (_mode.value as? Mode.At)?.let { (it.endsAtElapsed - now).coerceAtLeast(0L) }
}
