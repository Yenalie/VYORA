package com.vyora.music.playback.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.vyora.music.data.settings.EqualizerSettings
import com.vyora.music.data.settings.SettingsRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class EqEngine {
    DYNAMICS,
    LEGACY,
}

data class EqCapabilities(
    val engine: EqEngine,
    val virtualizerSupported: Boolean,
)

object AudioEffects {
    private const val TAG = "VYORA-AudioFx"
    private const val PRIORITY = 0

    private const val LIMITER_ATTACK_MS = 1f
    private const val LIMITER_RELEASE_MS = 60f
    private const val LIMITER_RATIO = 10f
    private const val LIMITER_THRESHOLD_DB = -1f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _capabilities = MutableStateFlow<EqCapabilities?>(null)
    val capabilities: StateFlow<EqCapabilities?> = _capabilities.asStateFlow()

    private val _probed = MutableStateFlow(false)
    val probed: StateFlow<Boolean> = _probed.asStateFlow()

    private val _inControl = MutableStateFlow(true)
    val inControl: StateFlow<Boolean> = _inControl.asStateFlow()

    private var sessionId = 0
    private var dynamics: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var virtualizer: Virtualizer? = null
    private var observer: Job? = null

    fun probe(context: Context) {
        if (_probed.value) return
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val session = runCatching { am.generateAudioSessionId() }.getOrDefault(0)
        if (session > 0) {
            val dp = runCatching { createDynamics(session) }.getOrNull()
            val eq = if (dp == null) runCatching { Equalizer(PRIORITY, session) }.getOrNull() else null
            val virt = runCatching { Virtualizer(PRIORITY, session) }.getOrNull()
            _capabilities.value = when {
                dp != null -> EqCapabilities(EqEngine.DYNAMICS, virt != null)
                eq != null -> EqCapabilities(EqEngine.LEGACY, virt != null)
                else -> null
            }
            listOf(dp, eq, virt).forEach { runCatching { it?.release() } }
        }
        _probed.value = true
    }

    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == sessionId) return
        releaseEffects()
        sessionId = audioSessionId

        dynamics = runCatching { createDynamics(audioSessionId) }
            .onFailure { Log.w(TAG, "DynamicsProcessing unavailable, using Equalizer", it) }
            .getOrNull()
        if (dynamics == null) {
            equalizer = runCatching { Equalizer(PRIORITY, audioSessionId) }.onFailure { Log.w(TAG, "Equalizer", it) }.getOrNull()
            loudness = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        }
        virtualizer = runCatching { Virtualizer(PRIORITY, audioSessionId) }.getOrNull()

        _capabilities.value = when {
            dynamics != null -> EqCapabilities(EqEngine.DYNAMICS, virtualizer != null)
            equalizer != null -> EqCapabilities(EqEngine.LEGACY, virtualizer != null)
            else -> null
        }
        _probed.value = true
        _inControl.value = true
        listOfNotNull(dynamics, equalizer).forEach { effect ->
            effect.setControlStatusListener { _, hasControl -> _inControl.value = hasControl }
        }

        observer?.cancel()
        observer = scope.launch {
            SettingsRepository.settings.map { it.equalizer }.distinctUntilChanged().collect(::apply)
        }
    }

    fun release() {
        observer?.cancel()
        observer = null
        releaseEffects()
        sessionId = 0
    }

    private fun releaseEffects() {
        listOf(dynamics, equalizer, loudness, virtualizer).forEach { runCatching { it?.release() } }
        dynamics = null; equalizer = null; loudness = null; virtualizer = null
    }

    private fun createDynamics(session: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
             true, EqBands.COUNT,
             false, 0,
             false, 0,
             true,
        ).build()
        return DynamicsProcessing(PRIORITY, session, config)
    }

    private fun apply(s: EqualizerSettings) {
        val gains = EqBands.gains(s)
        val input = EqBands.inputGainDb(s, gains)
        dynamics?.let { dp -> runCatching { applyDynamics(dp, s, gains, input) }.onFailure { Log.w(TAG, "apply dynamics", it) } }
        equalizer?.let { eq -> runCatching { applyLegacy(eq, s, gains, input) }.onFailure { Log.w(TAG, "apply equalizer", it) } }
        virtualizer?.let { v ->
            runCatching {
                val on = s.enabled && s.virtualizer > 0.01f
                if (on && v.strengthSupported) v.setStrength((s.virtualizer * 1000).roundToInt().toShort())
                v.enabled = on
            }
        }
    }

    private fun applyDynamics(dp: DynamicsProcessing, s: EqualizerSettings, gains: FloatArray, inputDb: Float) {
        val eq = DynamicsProcessing.Eq(true, true, EqBands.COUNT)
        for (i in 0 until EqBands.COUNT) {
            eq.setBand(i, DynamicsProcessing.EqBand(true, EqBands.CUTOFFS_HZ[i], gains[i]))
        }
        dp.setPreEqAllChannelsTo(eq)
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true, true, 0,
                LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO, LIMITER_THRESHOLD_DB, 0f,
            )
        )
        dp.setInputGainAllChannelsTo(inputDb)
        dp.enabled = s.enabled
    }

    private fun applyLegacy(eq: Equalizer, s: EqualizerSettings, gains: FloatArray, inputDb: Float) {
        val range = eq.bandLevelRange
        val minDb = range[0] / 100f
        val maxDb = range[1] / 100f
        val shift = inputDb.coerceAtMost(0f)
        for (band in 0 until eq.numberOfBands) {
            val hz = eq.getCenterFreq(band.toShort()) / 1000
            val db = (EqBands.valueAt(gains, hz) + shift).coerceIn(minDb, maxDb)
            eq.setBandLevel(band.toShort(), (db * 100).roundToInt().toShort())
        }
        eq.enabled = s.enabled
        loudness?.let { l ->
            val on = s.enabled && s.loudness > 0.05f
            if (on) l.setTargetGain((s.loudness * 100).roundToInt())
            l.enabled = on
        }
    }

    private const val CHANNELS = 2
}
