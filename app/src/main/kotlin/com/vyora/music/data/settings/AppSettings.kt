package com.vyora.music.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.vyora.music.playback.audio.EqBands
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AccentChoice(val argb: Long) {
    RED(0xFFFA2D48),
    PINK(0xFFFF375F),
    ORANGE(0xFFFF9F0A),
    GREEN(0xFF30D158),
    TEAL(0xFF40C8E0),
    BLUE(0xFF0A84FF),
    INDIGO(0xFF5E5CE6),
    PURPLE(0xFFBF5AF2),
}

enum class GlassLook { LIQUID, FROSTED, TINTED }

enum class AudioQuality { HIGH, NORMAL, LOW }

enum class CacheSize(val megabytes: Long) {
    MB_128(128),
    MB_256(256),
    MB_512(512),
    GB_1(1024),
    GB_2(2048);

    val bytes: Long get() = megabytes * 1024L * 1024L
}

enum class VideoArtwork { ALWAYS, WIFI_ONLY, OFF }

enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", ""),
    ENGLISH("en", "English"),
    INDONESIAN("id", "Bahasa Indonesia"),
    MALAY("ms", "Bahasa Melayu"),
    SPANISH("es", "Español"),
    JAPANESE("ja", "日本語"),
}

data class GlassSettings(
    val enabled: Boolean = true,
    val style: GlassLook = GlassLook.LIQUID,
    val blur: Float = 4f,
    val refraction: Float = 0.65f,
    val lensHeight: Float = 0.45f,
    val vibrancy: Float = 1.5f,
    val highlight: Float = 0.7f,
    val tint: Float = 0.5f,
    val chromaticAberration: Boolean = false,
    val depth: Boolean = false,
    val navBar: Boolean = true,
    val miniPlayer: Boolean = true,
    val content: Boolean = true,
)

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: String = "flat",
    val customGains: List<Float> = emptyList(),
    val bassBoost: Float = 0f,
    val virtualizer: Float = 0f,
    val loudness: Float = 0f,
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentChoice = AccentChoice.RED,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val ambientBackground: Boolean = true,
    val animatedPlayerBackground: Boolean = true,
    val haptics: Boolean = true,
    val glass: GlassSettings = GlassSettings(),
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    /** Quality on metered networks (mobile data, hotspot); [audioQuality] applies on Wi-Fi. */
    val mobileQuality: AudioQuality = AudioQuality.NORMAL,
    val separateMobileQuality: Boolean = false,
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 1f,
    val scrobbleLastFm: Boolean = false,
    val scrobbleListenBrainz: Boolean = false,
    val skipSilence: Boolean = false,
    val pauseOnDisconnect: Boolean = true,
    val skipUnavailable: Boolean = true,
    val autoplay: Boolean = true,
    val crossfade: Boolean = false,
    val crossfadeSeconds: Float = 6f,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val videoArtwork: VideoArtwork = VideoArtwork.ALWAYS,
    val mediaCache: CacheSize = CacheSize.MB_512,
    val autoUpdateCheck: Boolean = true,
    val downloadWifiOnly: Boolean = false,
    val discordPresence: Boolean = false,
)

/** The streaming quality to use right now: the mobile-data choice on metered networks when the user split them. */
fun AppSettings.effectiveQuality(metered: Boolean): AudioQuality =
    if (separateMobileQuality && metered) mobileQuality else audioQuality

object SettingsRepository {
    private const val PREFS = "vyora_settings"
    private const val WRITE_DELAY_MS = 300L

    private const val EQ_VERSION = 2

    private lateinit var prefs: SharedPreferences
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vyora-settings").apply { isDaemon = true } }
    private var pendingWrite: ScheduledFuture<*>? = null
    private val state = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = read(prefs)
    }

    fun languageTag(context: Context): String {
        init(context)
        return state.value.language.tag
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        state.update(transform)
        if (::prefs.isInitialized) scheduleWrite()
    }

    @Synchronized
    private fun scheduleWrite() {
        pendingWrite?.cancel(false)
        pendingWrite = writer.schedule({ write(prefs, state.value) }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun flush() {
        if (!::prefs.isInitialized) return
        val pending = pendingWrite ?: return
        if (pending.cancel(false)) write(prefs, state.value)
        pendingWrite = null
    }

    fun updateGlass(transform: (GlassSettings) -> GlassSettings) =
        update { it.copy(glass = transform(it.glass)) }

    fun updateEqualizer(transform: (EqualizerSettings) -> EqualizerSettings) =
        update { it.copy(equalizer = transform(it.equalizer)) }

    fun resetGlass() = update { it.copy(glass = GlassSettings()) }

    fun resetAll() = update { AppSettings(language = it.language) }

    /** Every stored preference, for backups. */
    @Synchronized
    fun exportRaw(): Map<String, Any?> {
        flush()
        return if (::prefs.isInitialized) prefs.all.toMap() else emptyMap()
    }

    /** Replaces stored preferences with a backup's and reloads them. */
    @Synchronized
    fun importRaw(values: Map<String, Any?>) {
        if (!::prefs.isInitialized) return
        pendingWrite?.cancel(false)
        pendingWrite = null
        prefs.edit(commit = true) {
            clear()
            values.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Float -> putFloat(k, v)
                    is Double -> putFloat(k, v.toFloat())
                    is String -> putString(k, v)
                }
            }
        }
        state.value = read(prefs)
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, default: T): T =
        getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun read(p: SharedPreferences): AppSettings {
        val d = AppSettings()
        val g = d.glass
        val e = d.equalizer
        return AppSettings(
            themeMode = p.enum("theme", d.themeMode),
            accent = p.enum("accent", d.accent),
            language = p.enum("language", d.language),
            ambientBackground = p.getBoolean("ambient", d.ambientBackground),
            animatedPlayerBackground = p.getBoolean("player_bg", d.animatedPlayerBackground),
            haptics = p.getBoolean("haptics", d.haptics),
            glass = GlassSettings(
                enabled = p.getBoolean("glass_enabled", g.enabled),
                style = p.enum("glass_style", g.style),
                blur = p.getFloat("glass_blur", g.blur),
                refraction = p.getFloat("glass_refraction", g.refraction),
                lensHeight = p.getFloat("glass_lens_height", g.lensHeight),
                vibrancy = p.getFloat("glass_vibrancy", g.vibrancy),
                highlight = p.getFloat("glass_highlight", g.highlight),
                tint = p.getFloat("glass_tint", g.tint),
                chromaticAberration = p.getBoolean("glass_ca", g.chromaticAberration),
                depth = p.getBoolean("glass_depth", g.depth),
                navBar = p.getBoolean("glass_nav", g.navBar),
                miniPlayer = p.getBoolean("glass_mini", g.miniPlayer),
                content = p.getBoolean("glass_content", g.content),
            ),
            audioQuality = p.enum("audio_quality", d.audioQuality),
            mobileQuality = p.enum("audio_quality_mobile", d.mobileQuality),
            separateMobileQuality = p.getBoolean("audio_quality_split", d.separateMobileQuality),
            playbackSpeed = p.getFloat("playback_speed", d.playbackSpeed).coerceIn(0.5f, 2f),
            playbackPitch = p.getFloat("playback_pitch", d.playbackPitch).coerceIn(0.5f, 2f),
            scrobbleLastFm = p.getBoolean("scrobble_lastfm", d.scrobbleLastFm),
            scrobbleListenBrainz = p.getBoolean("scrobble_lb", d.scrobbleListenBrainz),
            skipSilence = p.getBoolean("skip_silence", d.skipSilence),
            pauseOnDisconnect = p.getBoolean("pause_noisy", d.pauseOnDisconnect),
            skipUnavailable = p.getBoolean("skip_unavailable", d.skipUnavailable),
            autoplay = p.getBoolean("autoplay", d.autoplay),
            crossfade = p.getBoolean("crossfade", d.crossfade),
            crossfadeSeconds = p.getFloat("crossfade_sec", d.crossfadeSeconds).coerceIn(1f, 12f),
            equalizer = EqualizerSettings(
                enabled = p.getBoolean("eq_enabled", e.enabled),
                preset = p.getString("eq_preset", e.preset) ?: e.preset,
                customGains = p.getString("eq_custom", "").orEmpty()
                    .split(',').mapNotNull { it.trim().toFloatOrNull() }
                    .let { if (p.getInt("eq_version", 1) < EQ_VERSION) EqBands.migrateLegacy(it) else it },
                bassBoost = p.getFloat("eq_bass", e.bassBoost),
                virtualizer = p.getFloat("eq_virt", e.virtualizer),
                loudness = p.getFloat("eq_loud", e.loudness),
            ),
            videoArtwork = p.enum("video_artwork", d.videoArtwork),
            mediaCache = p.enum("media_cache", d.mediaCache),
            autoUpdateCheck = p.getBoolean("update_auto", d.autoUpdateCheck),
            downloadWifiOnly = p.getBoolean("download_wifi_only", d.downloadWifiOnly),
            discordPresence = p.getBoolean("discord_presence", d.discordPresence),
        )
    }

    private fun write(p: SharedPreferences, s: AppSettings) = p.edit {
        putString("theme", s.themeMode.name)
        putString("accent", s.accent.name)
        putString("language", s.language.name)
        putBoolean("ambient", s.ambientBackground)
        putBoolean("player_bg", s.animatedPlayerBackground)
        putBoolean("haptics", s.haptics)
        with(s.glass) {
            putBoolean("glass_enabled", enabled)
            putString("glass_style", style.name)
            putFloat("glass_blur", blur)
            putFloat("glass_refraction", refraction)
            putFloat("glass_lens_height", lensHeight)
            putFloat("glass_vibrancy", vibrancy)
            putFloat("glass_highlight", highlight)
            putFloat("glass_tint", tint)
            putBoolean("glass_ca", chromaticAberration)
            putBoolean("glass_depth", depth)
            putBoolean("glass_nav", navBar)
            putBoolean("glass_mini", miniPlayer)
            putBoolean("glass_content", content)
        }
        putString("audio_quality", s.audioQuality.name)
        putString("audio_quality_mobile", s.mobileQuality.name)
        putBoolean("audio_quality_split", s.separateMobileQuality)
        putFloat("playback_speed", s.playbackSpeed)
        putFloat("playback_pitch", s.playbackPitch)
        putBoolean("scrobble_lastfm", s.scrobbleLastFm)
        putBoolean("scrobble_lb", s.scrobbleListenBrainz)
        putBoolean("skip_silence", s.skipSilence)
        putBoolean("pause_noisy", s.pauseOnDisconnect)
        putBoolean("skip_unavailable", s.skipUnavailable)
        putBoolean("autoplay", s.autoplay)
        putBoolean("crossfade", s.crossfade)
        putFloat("crossfade_sec", s.crossfadeSeconds)
        with(s.equalizer) {
            putBoolean("eq_enabled", enabled)
            putString("eq_preset", preset)
            putString("eq_custom", customGains.joinToString(","))
            putFloat("eq_bass", bassBoost)
            putFloat("eq_virt", virtualizer)
            putFloat("eq_loud", loudness)
        }
        putInt("eq_version", EQ_VERSION)
        putString("video_artwork", s.videoArtwork.name)
        putString("media_cache", s.mediaCache.name)
        putBoolean("update_auto", s.autoUpdateCheck)
        putBoolean("download_wifi_only", s.downloadWifiOnly)
        putBoolean("discord_presence", s.discordPresence)
    }
}
