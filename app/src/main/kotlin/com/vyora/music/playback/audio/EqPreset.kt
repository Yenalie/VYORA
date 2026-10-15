package com.vyora.music.playback.audio

import com.vyora.music.data.settings.EqualizerSettings
import kotlin.math.ln
import kotlin.math.max

object EqBands {
    val FREQUENCIES_HZ = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    const val COUNT = 10
    const val MIN_DB = -12f
    const val MAX_DB = 12f

    const val BASS_BOOST_MAX_DB = 8f
    private val BASS_SHELF = floatArrayOf(1f, 0.9f, 0.6f, 0.25f, 0.05f, 0f, 0f, 0f, 0f, 0f)

    private const val HEADROOM_SHARE = 0.75f

    fun gains(s: EqualizerSettings): FloatArray {
        val curve = curve(s)
        val bass = s.bassBoost.coerceIn(0f, 1f) * BASS_BOOST_MAX_DB
        return FloatArray(COUNT) { i -> (curve[i] + bass * BASS_SHELF[i]).coerceIn(MIN_DB, MAX_DB + BASS_BOOST_MAX_DB) }
    }

    fun curve(s: EqualizerSettings): FloatArray {
        EqPreset.byId(s.preset)?.let { return it.gains.copyOf() }
        return if (s.customGains.size == COUNT) {
            FloatArray(COUNT) { s.customGains[it].coerceIn(MIN_DB, MAX_DB) }
        } else {
            FloatArray(COUNT)
        }
    }

    fun inputGainDb(s: EqualizerSettings, bandGains: FloatArray = gains(s)): Float {
        val peak = max(0f, bandGains.maxOrNull() ?: 0f)
        return s.loudness.coerceIn(0f, 10f) - peak * HEADROOM_SHARE
    }

    val CUTOFFS_HZ: List<Float> = FREQUENCIES_HZ.mapIndexed { i, f ->
        FREQUENCIES_HZ.getOrNull(i + 1)?.let { kotlin.math.sqrt(f.toFloat() * it) } ?: 20_000f
    }

    fun valueAt(curve: FloatArray, hz: Int): Float = interpolate(FREQUENCIES_HZ, curve, hz)

    fun migrateLegacy(old: List<Float>): List<Float> {
        if (old.isEmpty() || old.size == COUNT) return old
        val freqs = if (old.size == LEGACY_HZ.size) LEGACY_HZ else spread(old.size)
        val curve = old.toFloatArray()
        return FREQUENCIES_HZ.map { interpolate(freqs, curve, it).coerceIn(MIN_DB, MAX_DB) }
    }

    private val LEGACY_HZ = listOf(60, 230, 910, 3_600, 14_000)

    private fun spread(n: Int): List<Int> {
        if (n == 1) return listOf(1_000)
        val lo = ln(60.0); val hi = ln(14_000.0)
        return List(n) { i -> kotlin.math.exp(lo + (hi - lo) * i / (n - 1)).toInt() }
    }

    private fun interpolate(freqs: List<Int>, values: FloatArray, hz: Int): Float {
        val x = ln(hz.coerceAtLeast(20).toDouble())
        val xs = freqs.map { ln(it.toDouble()) }
        if (x <= xs.first()) return values.first()
        if (x >= xs.last()) return values.last()
        val i = xs.indexOfLast { it <= x }
        val t = ((x - xs[i]) / (xs[i + 1] - xs[i])).toFloat()
        return values[i] + (values[i + 1] - values[i]) * t
    }
}

enum class EqPreset(val id: String, val gains: FloatArray) {
    FLAT("flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS("bass", floatArrayOf(6f, 5.5f, 4.5f, 2.5f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE("treble", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 3.5f, 5f, 6f)),
    VOCAL("vocal", floatArrayOf(-2f, -2f, -1f, 0.5f, 2.5f, 3.5f, 3f, 2f, 0.5f, 0f)),
    POP("pop", floatArrayOf(-1f, 0f, 1.5f, 3f, 3.5f, 2.5f, 1f, 0f, -0.5f, -1f)),
    ROCK("rock", floatArrayOf(4.5f, 4f, 2.5f, 0f, -1.5f, -1f, 1f, 2.5f, 3.5f, 4.5f)),
    JAZZ("jazz", floatArrayOf(3f, 2.5f, 1f, 1.5f, -1f, -1f, 0f, 1.5f, 3f, 3.5f)),
    CLASSICAL("classical", floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    ELECTRONIC("electronic", floatArrayOf(5f, 4.5f, 2f, 0f, -1.5f, 1f, 0.5f, 1.5f, 4f, 5f)),
    HIPHOP("hiphop", floatArrayOf(5f, 4.5f, 2.5f, 2f, -0.5f, -0.5f, 1f, 0f, 1.5f, 2.5f)),
    ACOUSTIC("acoustic", floatArrayOf(3f, 2.5f, 1.5f, 1f, 1f, 1f, 2f, 2.5f, 2.5f, 2f)),
    LATE_NIGHT("late_night", floatArrayOf(2f, 2f, 1.5f, 1f, 2f, 1.5f, 0.5f, -1f, -2f, -3f));

    companion object {
        const val CUSTOM = "custom"
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}
