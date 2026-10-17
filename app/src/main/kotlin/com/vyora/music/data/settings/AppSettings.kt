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

/**
 * Streaming quality ladder, ported from BitChord's four rungs (Low / Medium / High / Lossless).
 * Stored by [name], so the original HIGH / NORMAL / LOW names are kept and NORMAL is the "Medium" rung.
 *
 * - YouTube: [maxKbps] caps the Opus/AAC ladder; MEDIUM and above take the best rendition on offer.
 * - Music server: [serverKbps] asks the server to transcode; `null` streams the original file bit for bit.
 *   Only [LOSSLESS] lets a lossless file through untouched.
 * - Device files are always played as they are.
 */
enum class AudioQuality(val maxKbps: Int, val serverKbps: Int?, val hourlyMb: Int) {
    LOW(64, 128, 29),
    NORMAL(140, 192, 77),
    HIGH(Int.MAX_VALUE, 320, 144),
    LOSSLESS(Int.MAX_VALUE, null, 300);

    val keepsLossless: Boolean get() = serverKbps == null
}

/**
 * What a download keeps (BitChord's DownloadQuality). Priced per track rather than per hour, because a
 * download is paid for once. [LOSSLESS] is the only rung that keeps a server's original file.
 */
enum class DownloadQuality(val stream: AudioQuality, val perTrackMb: Int) {
    STANDARD(AudioQuality.NORMAL, 4),
    HIGH(AudioQuality.HIGH, 8),
    LOSSLESS(AudioQuality.LOSSLESS, 35),
}

/**
 * PCM format requested at the AudioTrack boundary (BitChord's OutputPcmMode). [FLOAT_32] hands hi-res
 * (24/32-bit) sources to Android as 32-bit float instead of truncating them to 16 bits; it is only used on
 * an external route that advertises PCM float, never on the phone's own speaker.
 */
enum class OutputPcmMode { PCM_16, FLOAT_32 }

enum class CacheSize(val megabytes: Long) {
    MB_128(128),
    MB_256(256),
    MB_512(512),
    GB_1(1024),
    GB_2(2048);

    val bytes: Long get() = megabytes * 1024L * 1024L
}

enum class VideoArtwork { ALWAYS, WIFI_ONLY, OFF }

/** Online status Discord shows while VYORA's activity is set. */
enum class DiscordOnlineStatus(val wire: String) { ONLINE("online"), IDLE("idle"), DND("dnd") }

/** Activity verb on the Discord profile ("Listening to" / "Playing"). */
enum class DiscordActivityKind(val wire: Int) { LISTENING(2), PLAYING(0) }

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
    val audioQuality: AudioQuality = AudioQuality.LOSSLESS,
    /** Quality on metered networks (mobile data, hotspot); [audioQuality] applies on Wi-Fi. */
    val mobileQuality: AudioQuality = AudioQuality.NORMAL,
    val separateMobileQuality: Boolean = true,
    val downloadQuality: DownloadQuality = DownloadQuality.HIGH,
    /** Copy finished downloads into Music/VYORA where other music apps can see them. */
    val exportDownloads: Boolean = false,
    val outputPcmMode: OutputPcmMode = OutputPcmMode.PCM_16,
    /** Route playback to an attached USB DAC even when Android would pick another output. */
    val preferUsbDac: Boolean = false,
    /** Sound Check: level every YouTube track to the same loudness using YouTube's own figure. */
    val loudnessNormalization: Boolean = true,
    /** Leave loudness alone while playing through the phone's own speaker. */
    val loudnessOffOnSpeaker: Boolean = true,
    /** Stereo widening + cross-feed inside the player (BitChord's spatial audio). */
    val spatialAudio: Boolean = false,
    /** Once a song was suggested by Autoplay this session, never suggest it again. */
    val dontRepeatSuggestions: Boolean = true,
    /** Autoplay and mixes prefer the catalogue song over its music-video upload. */
    val preferSongsOverVideos: Boolean = true,
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
    val discordButtons: Boolean = true,
    val discordShowPaused: Boolean = true,
    val discordStatus: DiscordOnlineStatus = DiscordOnlineStatus.ONLINE,
    val discordActivity: DiscordActivityKind = DiscordActivityKind.LISTENING,
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

    /** Bumped when the quality ladder gained LOSSLESS (1.10.0). */
    private const val QUALITY_LADDER = 2

    private fun read(p: SharedPreferences): AppSettings = migrate(p, readStored(p))

    /**
     * Before the ladder had a Lossless rung, HIGH streamed the server's original file. Keep that for
     * people updating, and give anyone who never split Wi-Fi from mobile the same choice on both.
     */
    internal fun migrate(p: SharedPreferences, s: AppSettings): AppSettings {
        if (p.getInt("quality_ladder", 0) >= QUALITY_LADDER || !p.contains("audio_quality")) return s
        val wifi = if (s.audioQuality == AudioQuality.HIGH) AudioQuality.LOSSLESS else s.audioQuality
        val split = p.getBoolean("audio_quality_split", false)
        val mobile = if (split) s.mobileQuality.let { if (it == AudioQuality.HIGH) AudioQuality.LOSSLESS else it } else wifi
        return s.copy(audioQuality = wifi, mobileQuality = mobile)
    }

    private fun readStored(p: SharedPreferences): AppSettings {
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
            separateMobileQuality = true,
            downloadQuality = p.enum("download_quality", d.downloadQuality),
            exportDownloads = p.getBoolean("download_export", d.exportDownloads),
            outputPcmMode = p.enum("output_pcm", d.outputPcmMode),
            preferUsbDac = p.getBoolean("prefer_usb_dac", d.preferUsbDac),
            loudnessNormalization = p.getBoolean("loudness_norm", d.loudnessNormalization),
            loudnessOffOnSpeaker = p.getBoolean("loudness_speaker_off", d.loudnessOffOnSpeaker),
            spatialAudio = p.getBoolean("spatial_audio", d.spatialAudio),
            dontRepeatSuggestions = p.getBoolean("autoplay_no_repeat", d.dontRepeatSuggestions),
            preferSongsOverVideos = p.getBoolean("autoplay_songs_only", d.preferSongsOverVideos),
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
            discordButtons = p.getBoolean("discord_buttons", d.discordButtons),
            discordShowPaused = p.getBoolean("discord_show_paused", d.discordShowPaused),
            discordStatus = p.enum("discord_status", d.discordStatus),
            discordActivity = p.enum("discord_activity", d.discordActivity),
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
        putBoolean("audio_quality_split", true)
        putInt("quality_ladder", QUALITY_LADDER)
        putString("download_quality", s.downloadQuality.name)
        putBoolean("download_export", s.exportDownloads)
        putString("output_pcm", s.outputPcmMode.name)
        putBoolean("prefer_usb_dac", s.preferUsbDac)
        putBoolean("loudness_norm", s.loudnessNormalization)
        putBoolean("loudness_speaker_off", s.loudnessOffOnSpeaker)
        putBoolean("spatial_audio", s.spatialAudio)
        putBoolean("autoplay_no_repeat", s.dontRepeatSuggestions)
        putBoolean("autoplay_songs_only", s.preferSongsOverVideos)
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
        putBoolean("discord_buttons", s.discordButtons)
        putBoolean("discord_show_paused", s.discordShowPaused)
        putString("discord_status", s.discordStatus.name)
        putString("discord_activity", s.discordActivity.name)
    }
}
