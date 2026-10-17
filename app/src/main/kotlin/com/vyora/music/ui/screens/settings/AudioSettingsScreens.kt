package com.vyora.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vyora.music.R
import com.vyora.music.core.NetworkState
import com.vyora.music.data.local.LosslessOutput
import com.vyora.music.data.settings.AudioQuality
import com.vyora.music.data.settings.DownloadQuality
import com.vyora.music.data.settings.OutputPcmMode
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.playback.audio.OutputStatus
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Audio settings laid out like Apple Music's: Audio Quality holds the streaming rungs (one row per
 * connection, the one in force tagged "In use") and the download rung; each opens its own list.
 * The ladder, its data costs and the output page come from BitChord.
 */

/** Audio Quality: streaming per connection and downloads. */
@Composable
fun AudioQualityScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
    val settings by SettingsRepository.settings.collectAsState()
    val metered by NetworkState.metered.collectAsState()
    val inUse = stringResource(R.string.in_use)
    SettingsPage(title = stringResource(R.string.audio_quality), onBack = onBack) {
        item(key = "streaming") {
            SettingsGroup(
                header = stringResource(R.string.streaming_header),
                footer = stringResource(R.string.audio_quality_footer),
            ) {
                SettingsNavRow(
                    title = stringResource(R.string.streaming_wifi),
                    value = qualityLabel(settings.audioQuality),
                    badge = inUse.takeIf { !metered },
                    onClick = { onOpen("quality/wifi") },
                )
                SettingsNavRow(
                    title = stringResource(R.string.streaming_mobile),
                    value = qualityLabel(settings.mobileQuality),
                    badge = inUse.takeIf { metered },
                    onClick = { onOpen("quality/mobile") },
                    divider = false,
                )
            }
        }
        item(key = "downloads") {
            SettingsGroup(
                header = stringResource(R.string.downloads),
                footer = stringResource(R.string.download_quality_footer),
            ) {
                SettingsNavRow(
                    title = stringResource(R.string.download_quality),
                    value = downloadQualityLabel(settings.downloadQuality),
                    subtitle = stringResource(R.string.download_per_track, settings.downloadQuality.perTrackMb),
                    onClick = { onOpen("quality/download") },
                    divider = false,
                )
            }
        }
    }
}

/** One list of rungs, for Wi-Fi, mobile data or downloads ([target] is `wifi`, `mobile` or `download`). */
@Composable
fun QualityPickerScreen(target: String, onBack: () -> Unit) {
    val settings by SettingsRepository.settings.collectAsState()
    when (target) {
        "download" -> SettingsPage(title = stringResource(R.string.download_quality), onBack = onBack) {
            item(key = "rungs") {
                SettingsGroup(footer = stringResource(R.string.download_quality_footer)) {
                    DownloadQuality.entries.reversed().forEachIndexed { i, q ->
                        SettingsChoiceRow(
                            title = downloadQualityLabel(q),
                            subtitle = downloadQualityDetail(q) + " · " + stringResource(R.string.download_per_track, q.perTrackMb),
                            selected = settings.downloadQuality == q,
                            onClick = { SettingsRepository.update { it.copy(downloadQuality = q) } },
                            divider = i < DownloadQuality.entries.lastIndex,
                        )
                    }
                }
            }
        }
        else -> {
            val mobile = target == "mobile"
            val selected = if (mobile) settings.mobileQuality else settings.audioQuality
            SettingsPage(
                title = stringResource(if (mobile) R.string.streaming_mobile else R.string.streaming_wifi),
                onBack = onBack,
            ) {
                item(key = "rungs") {
                    SettingsGroup(footer = stringResource(R.string.audio_quality_footer)) {
                        AudioQuality.entries.reversed().forEachIndexed { i, q ->
                            SettingsChoiceRow(
                                title = qualityLabel(q),
                                subtitle = qualityDetail(q) + " · " + qualityHourly(q),
                                selected = selected == q,
                                onClick = {
                                    SettingsRepository.update {
                                        if (mobile) it.copy(mobileQuality = q, separateMobileQuality = true)
                                        else it.copy(audioQuality = q, separateMobileQuality = true)
                                    }
                                },
                                divider = i < AudioQuality.entries.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Audio Output: output precision, USB DAC, Sound Check, spatial audio and skip silence. */
@Composable
fun AudioOutputScreen(onBack: () -> Unit) {
    val settings by SettingsRepository.settings.collectAsState()
    val output by LosslessOutput.state.collectAsState()
    val status by OutputStatus.state.collectAsState()
    SettingsPage(title = stringResource(R.string.settings_output), onBack = onBack) {
        item(key = "precision") {
            SettingsGroup(
                header = stringResource(R.string.output_header),
                footer = stringResource(R.string.output_precision_footer),
            ) {
                SettingsRow(
                    title = stringResource(R.string.output_precision),
                    subtitle = outputStatusLine(output, status),
                    divider = false,
                )
                SettingsControlRow {
                    SegmentedControl(
                        options = OutputPcmMode.entries.toList(),
                        selected = settings.outputPcmMode,
                        onSelect = { m -> SettingsRepository.update { it.copy(outputPcmMode = m) } },
                        label = { outputModeLabel(it) },
                    )
                }
                SettingsSwitchRow(
                    title = stringResource(R.string.prefer_usb_dac),
                    subtitle = stringResource(R.string.prefer_usb_footer),
                    checked = settings.preferUsbDac,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(preferUsbDac = v) } },
                    badge = stringResource(R.string.connected).takeIf { output.usbConnected },
                    divider = false,
                )
            }
        }
        item(key = "sound_check") {
            SettingsGroup(
                header = stringResource(R.string.sound_check),
                footer = stringResource(R.string.sound_check_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.sound_check),
                    subtitle = status.loudnessGainDb?.takeIf { settings.loudnessNormalization }?.let { db ->
                        stringResource(R.string.sound_check_now, formatDb(db))
                    },
                    checked = settings.loudnessNormalization,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(loudnessNormalization = v) } },
                    divider = settings.loudnessNormalization,
                )
                if (settings.loudnessNormalization) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.sound_check_speaker),
                        checked = settings.loudnessOffOnSpeaker,
                        onCheckedChange = { v -> SettingsRepository.update { it.copy(loudnessOffOnSpeaker = v) } },
                        divider = false,
                    )
                }
            }
        }
        item(key = "effects") {
            SettingsGroup(header = stringResource(R.string.effects_header)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.spatial_audio),
                    subtitle = stringResource(R.string.spatial_audio_desc),
                    checked = settings.spatialAudio,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(spatialAudio = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.skip_silence),
                    subtitle = stringResource(R.string.skip_silence_float).takeIf { status.isFloat },
                    checked = settings.skipSilence,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(skipSilence = v) } },
                    divider = false,
                )
            }
        }
    }
}

/** "AudioTrack · USB DAC · 96 kHz · 32-bit float", or which output is connected while nothing plays. */
@Composable
private fun outputStatusLine(output: LosslessOutput.State, status: OutputStatus.State): String {
    val device = output.deviceName ?: stringResource(
        when (output.via) {
            LosslessOutput.Kind.WIRED -> R.string.output_wired
            LosslessOutput.Kind.USB -> R.string.output_usb
            LosslessOutput.Kind.HDMI -> R.string.output_hdmi
            LosslessOutput.Kind.DOCK -> R.string.output_dock
            LosslessOutput.Kind.BLUETOOTH -> R.string.output_bluetooth
            LosslessOutput.Kind.SPEAKER, null -> R.string.output_speaker
        }
    )
    if (!status.active) return listOf(device, stringResource(R.string.output_status_idle)).joinToString(" · ")
    val parts = mutableListOf(if (status.offload) "Offload" else "AudioTrack", device)
    if (status.sinkSampleRateHz > 0) parts += OutputStatus.kiloHertz(status.sinkSampleRateHz)
    OutputStatus.encodingLabel(status.sinkEncoding)?.let { parts += it }
    return parts.joinToString(" · ")
}

private fun formatDb(db: Float): String {
    val r = (db * 10).roundToInt() / 10f
    return when {
        abs(r) < 0.05f -> "0 dB"
        r > 0 -> "+$r dB"
        else -> "$r dB"
    }
}

@Composable
fun outputModeLabel(mode: OutputPcmMode) = stringResource(
    when (mode) {
        OutputPcmMode.PCM_16 -> R.string.output_pcm16
        OutputPcmMode.FLOAT_32 -> R.string.output_float
    }
)

@Composable
fun downloadQualityLabel(q: DownloadQuality) = stringResource(
    when (q) {
        DownloadQuality.STANDARD -> R.string.download_standard
        DownloadQuality.HIGH -> R.string.quality_high
        DownloadQuality.LOSSLESS -> R.string.quality_lossless
    }
)

@Composable
private fun downloadQualityDetail(q: DownloadQuality) = stringResource(
    when (q) {
        DownloadQuality.STANDARD -> R.string.download_detail_standard
        DownloadQuality.HIGH -> R.string.download_detail_high
        DownloadQuality.LOSSLESS -> R.string.download_detail_lossless
    }
)

@Composable
private fun qualityDetail(q: AudioQuality) = stringResource(
    when (q) {
        AudioQuality.LOW -> R.string.quality_detail_low
        AudioQuality.NORMAL -> R.string.quality_detail_normal
        AudioQuality.HIGH -> R.string.quality_detail_high
        AudioQuality.LOSSLESS -> R.string.quality_detail_lossless
    }
)

@Composable
private fun qualityHourly(q: AudioQuality) =
    if (q == AudioQuality.LOSSLESS) stringResource(R.string.quality_hourly_lossless)
    else stringResource(R.string.quality_hourly, q.hourlyMb)
