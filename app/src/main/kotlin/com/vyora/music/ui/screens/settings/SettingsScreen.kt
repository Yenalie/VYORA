@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package com.vyora.music.ui.screens.settings

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.FolderDelete
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.vyora.music.R
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.library.SearchHistory
import com.vyora.music.data.lyrics.LyricsRepository
import com.vyora.music.data.settings.AccentChoice
import com.vyora.music.data.settings.AppLanguage
import com.vyora.music.data.settings.AudioQuality
import com.vyora.music.data.settings.CacheSize
import com.vyora.music.data.settings.GlassLook
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.settings.ThemeMode
import com.vyora.music.data.settings.VideoArtwork
import com.vyora.music.data.update.UpdateManager
import com.vyora.music.data.update.UpdateState
import com.vyora.music.ui.components.updateErrorText
import com.vyora.music.data.youtube.StreamResolver
import com.vyora.music.playback.source.MediaCache
import com.vyora.music.ui.theme.SystemColors
import com.vyora.music.ui.theme.VyoraDestructive
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val settings by SettingsRepository.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    val clearedText = stringResource(R.string.cleared)
    fun toast() = Toast.makeText(context, clearedText, Toast.LENGTH_SHORT).show()
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { cacheUsage(context) } }

    SettingsPage(title = stringResource(R.string.settings), onBack = onBack) {
        item(key = "account") {
            SettingsGroup { AccountSummaryRow(onClick = { onOpen("account") }) }
        }
        item(key = "appearance") {
            SettingsGroup(header = stringResource(R.string.settings_appearance)) {
                SettingsControlRow(title = stringResource(R.string.settings_theme)) {
                    SegmentedControl(
                        options = ThemeMode.entries,
                        selected = settings.themeMode,
                        onSelect = { mode -> SettingsRepository.update { it.copy(themeMode = mode) } },
                        label = { themeLabel(it) },
                    )
                }
                SettingsControlRow(title = stringResource(R.string.settings_accent)) {
                    AccentSwatches(
                        selected = settings.accent,
                        onSelect = { accent -> SettingsRepository.update { it.copy(accent = accent) } },
                    )
                }
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_ambient),
                    subtitle = stringResource(R.string.settings_ambient_desc),
                    icon = Icons.Default.Palette, iconTint = SystemColors.Pink,
                    checked = settings.ambientBackground,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(ambientBackground = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_player_bg),
                    icon = Icons.Default.Wallpaper, iconTint = SystemColors.Purple,
                    checked = settings.animatedPlayerBackground,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(animatedPlayerBackground = v) } },
                    divider = false,
                )
            }
        }
        item(key = "video_artwork") {
            SettingsGroup(footer = stringResource(R.string.video_artwork_footer)) {
                SettingsControlRow(title = stringResource(R.string.video_artwork), divider = false) {
                    SegmentedControl(
                        options = VideoArtwork.entries,
                        selected = settings.videoArtwork,
                        onSelect = { mode -> SettingsRepository.update { it.copy(videoArtwork = mode) } },
                        label = { videoArtworkLabel(it) },
                    )
                }
            }
        }
        item(key = "glass") {
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.settings_liquid_glass),
                    icon = Icons.Default.WaterDrop, iconTint = SystemColors.Teal,
                    value = if (!settings.glass.enabled) stringResource(R.string.vyora_off) else glassLookLabel(settings.glass.style),
                    onClick = { onOpen("glass") },
                    divider = false,
                )
            }
        }
        item(key = "audio") {
            SettingsGroup(
                header = stringResource(R.string.settings_audio),
                footer = stringResource(R.string.crossfade_footer),
            ) {
                SettingsNavRow(
                    title = stringResource(R.string.settings_equalizer),
                    icon = Icons.Default.Equalizer, iconTint = SystemColors.Red,
                    value = stringResource(if (settings.equalizer.enabled) R.string.vyora_on else R.string.vyora_off),
                    onClick = { onOpen("equalizer") },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.crossfade),
                    icon = Icons.Default.Tune, iconTint = SystemColors.Pink,
                    checked = settings.crossfade,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(crossfade = v) } },
                    divider = settings.crossfade,
                )
                if (settings.crossfade) {
                    SettingsSliderRow(
                        title = stringResource(R.string.crossfade_duration),
                        value = settings.crossfadeSeconds,
                        onValueChange = { v -> SettingsRepository.update { it.copy(crossfadeSeconds = v.roundToInt().toFloat()) } },
                        valueRange = 1f..12f,
                        steps = 10,
                        valueLabel = stringResource(R.string.seconds_short, settings.crossfadeSeconds.roundToInt()),
                        divider = false,
                    )
                }
            }
        }
        item(key = "quality") {
            SettingsGroup(footer = stringResource(R.string.audio_quality_footer)) {
                SettingsControlRow(title = stringResource(R.string.audio_quality), divider = false) {
                    SegmentedControl(
                        options = AudioQuality.entries,
                        selected = settings.audioQuality,
                        onSelect = { q -> SettingsRepository.update { it.copy(audioQuality = q) } },
                        label = { qualityLabel(it) },
                    )
                }
            }
        }
        item(key = "playback") {
            SettingsGroup(
                header = stringResource(R.string.settings_playback),
                footer = stringResource(R.string.autoplay_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.autoplay),
                    icon = Icons.Default.AllInclusive, iconTint = SystemColors.Purple,
                    checked = settings.autoplay,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(autoplay = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.skip_silence),
                    icon = Icons.Default.GraphicEq, iconTint = SystemColors.Orange,
                    checked = settings.skipSilence,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(skipSilence = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.pause_on_disconnect),
                    icon = Icons.Default.Headphones, iconTint = SystemColors.Blue,
                    checked = settings.pauseOnDisconnect,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(pauseOnDisconnect = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.skip_unavailable),
                    icon = Icons.Default.SkipNext, iconTint = SystemColors.Indigo,
                    checked = settings.skipUnavailable,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(skipUnavailable = v) } },
                    divider = false,
                )
            }
        }
        item(key = "general") {
            SettingsGroup(header = stringResource(R.string.settings_general)) {
                SettingsNavRow(
                    title = stringResource(R.string.settings_language),
                    icon = Icons.Default.Language, iconTint = SystemColors.Blue,
                    value = languageLabel(settings.language),
                    onClick = { onOpen("language") },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_haptics),
                    icon = Icons.Default.Vibration, iconTint = SystemColors.Gray,
                    checked = settings.haptics,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(haptics = v) } },
                    divider = false,
                )
            }
        }
        item(key = "storage") {
            SettingsGroup(header = stringResource(R.string.settings_storage)) {
                SettingsRow(
                    title = stringResource(R.string.clear_search_history),
                    icon = Icons.Default.SearchOff, iconTint = SystemColors.Gray,
                    onClick = { SearchHistory.clear(); toast() },
                )
                SettingsRow(
                    title = stringResource(R.string.clear_listening_history),
                    icon = Icons.Default.History, iconTint = SystemColors.Gray,
                    onClick = { confirm = ConfirmAction.HISTORY },
                )
                SettingsSliderRow(
                    title = stringResource(R.string.media_cache_size),
                    value = settings.mediaCache.ordinal.toFloat(),
                    onValueChange = { v ->
                        val size = CacheSize.entries[v.roundToInt().coerceIn(0, CacheSize.entries.lastIndex)]
                        SettingsRepository.update { it.copy(mediaCache = size) }
                    },
                    valueRange = 0f..CacheSize.entries.lastIndex.toFloat(),
                    steps = CacheSize.entries.size - 2,
                    valueLabel = cacheSizeLabel(settings.mediaCache),
                )
                SettingsRow(
                    title = stringResource(R.string.clear_cache),
                    subtitle = stringResource(
                        R.string.clear_cache_usage,
                        Formatter.formatShortFileSize(context, cacheBytes ?: 0L),
                    ),
                    icon = Icons.Default.DeleteSweep, iconTint = SystemColors.Gray,
                    divider = false,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                context.imageLoader.diskCache?.clear()
                                MediaCache.clear(context)
                                StreamResolver.clear()
                                LyricsRepository.clearCache()
                            }
                            context.imageLoader.memoryCache?.clear()
                            cacheBytes = withContext(Dispatchers.IO) { cacheUsage(context) }
                            toast()
                        }
                    },
                )
            }
        }
        item(key = "downloads") {
            val downloads by com.vyora.music.data.download.Downloads.entries.collectAsState()
            var offlineBytes by remember { mutableStateOf(0L) }
            LaunchedEffect(downloads.size) {
                offlineBytes = withContext(Dispatchers.IO) { com.vyora.music.data.download.Downloads.usedBytes(context) }
            }
            SettingsGroup(header = stringResource(R.string.downloads)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.download_wifi_only),
                    icon = Icons.Default.Wifi, iconTint = SystemColors.Blue,
                    checked = settings.downloadWifiOnly,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(downloadWifiOnly = v) } },
                )
                SettingsRow(
                    title = stringResource(R.string.downloads_remove_all),
                    subtitle = stringResource(
                        R.string.downloads_usage,
                        downloads.size,
                        Formatter.formatShortFileSize(context, offlineBytes),
                    ),
                    icon = Icons.Default.FolderDelete, iconTint = SystemColors.Gray,
                    divider = false,
                    onClick = { if (downloads.isNotEmpty()) confirm = ConfirmAction.DOWNLOADS },
                )
            }
        }
        item(key = "integrations") {
            SettingsGroup(header = stringResource(R.string.settings_integrations)) {
                SettingsNavRow(
                    title = stringResource(R.string.discord_title),
                    icon = Icons.Default.Gamepad, iconTint = Color(0xFF5865F2),
                    value = stringResource(if (settings.discordPresence) R.string.on else R.string.off),
                    onClick = { onOpen("discord") },
                    divider = false,
                )
            }
        }
        item(key = "updates") {
            UpdatesGroup(autoCheck = settings.autoUpdateCheck)
        }
        item(key = "about") {
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.settings_about),
                    icon = Icons.Default.Info, iconTint = SystemColors.Gray,
                    onClick = { onOpen("about") },
                )
                SettingsActionRow(
                    title = stringResource(R.string.reset_settings),
                    destructive = true,
                    icon = Icons.Default.RestartAlt, iconTint = SystemColors.Red,
                    onClick = { confirm = ConfirmAction.RESET },
                    divider = false,
                )
            }
        }
    }

    confirm?.let { action ->
        ConfirmDialog(
            message = stringResource(
                when (action) {
                    ConfirmAction.RESET -> R.string.confirm_reset
                    ConfirmAction.HISTORY -> R.string.confirm_clear_history
                    ConfirmAction.DOWNLOADS -> R.string.confirm_remove_downloads
                }
            ),
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                when (action) {
                    ConfirmAction.RESET -> SettingsRepository.resetAll()
                    ConfirmAction.HISTORY -> LibraryManager.clearRecent()
                    ConfirmAction.DOWNLOADS -> com.vyora.music.data.download.Downloads.removeAll(context)
                }
                toast()
            },
        )
    }
}

private enum class ConfirmAction { HISTORY, RESET, DOWNLOADS }

@Composable
private fun UpdatesGroup(autoCheck: Boolean) {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    val version = UpdateManager.currentVersion?.toString() ?: com.vyora.music.BuildConfig.VERSION_NAME
    val status = when (val s = state) {
        UpdateState.Idle -> stringResource(R.string.update_current_version, version)
        UpdateState.Checking -> stringResource(R.string.update_checking)
        UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, version)
        is UpdateState.Available -> stringResource(R.string.update_new_version, s.release.version.toString())
        is UpdateState.Downloading ->
            if (s.progress < 0f) stringResource(R.string.update_downloading)
            else stringResource(R.string.update_downloading_percent, (s.progress * 100).toInt())
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready, s.release.version.toString())
        is UpdateState.Failed -> updateErrorText(s.reason)
    }
    SettingsGroup(
        header = stringResource(R.string.settings_updates),
        footer = stringResource(R.string.update_footer),
    ) {
        SettingsRow(
            title = stringResource(R.string.update_check_now),
            subtitle = status,
            icon = Icons.Default.SystemUpdate, iconTint = SystemColors.Green,
            onClick = {
                when (state) {
                    is UpdateState.Available, is UpdateState.Downloading, is UpdateState.ReadyToInstall -> UpdateManager.showPrompt()
                    UpdateState.Checking -> Unit
                    else -> UpdateManager.check(context, manual = true)
                }
            },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.update_auto_check),
            icon = Icons.Default.Update, iconTint = SystemColors.Blue,
            checked = autoCheck,
            onCheckedChange = { v -> UpdateManager.setAutoCheck(context, v) },
            divider = false,
        )
    }
}

private fun cacheUsage(context: Context): Long =
    runCatching { MediaCache.usedBytes(context) }.getOrDefault(0L) + (context.imageLoader.diskCache?.size ?: 0L)

private fun cacheSizeLabel(size: CacheSize): String =
    if (size.megabytes >= 1024) "${size.megabytes / 1024} GB" else "${size.megabytes} MB"

@Composable
private fun videoArtworkLabel(mode: VideoArtwork) = stringResource(
    when (mode) {
        VideoArtwork.ALWAYS -> R.string.video_artwork_always
        VideoArtwork.WIFI_ONLY -> R.string.video_artwork_wifi
        VideoArtwork.OFF -> R.string.vyora_off
    }
)

@Composable
fun ConfirmDialog(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        textContentColor = VyoraTextMuted,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        title = { Text(stringResource(R.string.confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_continue), color = VyoraDestructive) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = VyoraText) }
        },
    )
}

@Composable
private fun AccentSwatches(selected: AccentChoice, onSelect: (AccentChoice) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        AccentChoice.entries.forEach { choice ->
            val color = Color(choice.argb)
            val isSelected = choice == selected
            val ring by animateDpAsState(if (isSelected) 2.5.dp else 0.dp, label = "accentRing")
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(ring, VyoraText.copy(alpha = 0.85f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, role = Role.RadioButton,
                    ) { onSelect(choice) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (isSelected) 26.dp else 34.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun themeLabel(mode: ThemeMode) = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)

@Composable
fun qualityLabel(q: AudioQuality) = stringResource(
    when (q) {
        AudioQuality.HIGH -> R.string.quality_high
        AudioQuality.NORMAL -> R.string.quality_normal
        AudioQuality.LOW -> R.string.quality_low
    }
)

@Composable
fun glassLookLabel(look: GlassLook) = stringResource(
    when (look) {
        GlassLook.LIQUID -> R.string.glass_style_liquid
        GlassLook.FROSTED -> R.string.glass_style_frosted
        GlassLook.TINTED -> R.string.glass_style_tinted
    }
)

@Composable
fun languageLabel(language: AppLanguage) =
    if (language == AppLanguage.SYSTEM) stringResource(R.string.language_system) else language.nativeName
