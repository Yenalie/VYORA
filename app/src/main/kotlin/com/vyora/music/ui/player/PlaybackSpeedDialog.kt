package com.vyora.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraSeparator
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SpeedPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** "1×", "1.25×": the label on the Now Playing speed button. */
internal fun speedLabel(speed: Float): String {
    val rounded = (speed * 100).roundToInt() / 100f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else "%.2f".format(Locale.ROOT, rounded).trimEnd('0').trimEnd('.')
    return "$text×"
}

private fun snap(v: Float): Float = ((v * 20).roundToInt() / 20f).coerceIn(0.5f, 2f)

/** Playback speed and pitch, opened from the Now Playing screen (the values still persist in settings). */
@Composable
fun PlaybackSpeedDialog(onDismiss: () -> Unit) {
    val settings by SettingsRepository.settings.collectAsState()
    val speed = settings.playbackSpeed
    val pitch = settings.playbackPitch
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        textContentColor = VyoraTextMuted,
        shape = RoundedCornerShape(26.dp),
        title = { Text(stringResource(R.string.playback_speed)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    speedLabel(speed),
                    color = VyoraText,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpeedPresets.forEach { p ->
                        val selected = abs(p - speed) < 0.01f
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) VyoraAccent else VyoraSeparator.copy(alpha = 0.6f))
                                .clickable { SettingsRepository.update { it.copy(playbackSpeed = p) } }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                speedLabel(p),
                                color = if (selected) Color.White else VyoraText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Slider(
                    value = speed,
                    onValueChange = { v -> SettingsRepository.update { it.copy(playbackSpeed = snap(v)) } },
                    valueRange = 0.5f..2f,
                    colors = SliderDefaults.colors(thumbColor = VyoraAccent, activeTrackColor = VyoraAccent),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.playback_pitch), color = VyoraText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(speedLabel(pitch), color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = pitch,
                    onValueChange = { v -> SettingsRepository.update { it.copy(playbackPitch = snap(v)) } },
                    valueRange = 0.5f..2f,
                    colors = SliderDefaults.colors(thumbColor = VyoraAccent, activeTrackColor = VyoraAccent),
                )
                Text(stringResource(R.string.speed_footer), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done), color = VyoraAccent) } },
        dismissButton = {
            TextButton(
                enabled = speed != 1f || pitch != 1f,
                onClick = { SettingsRepository.update { it.copy(playbackSpeed = 1f, playbackPitch = 1f) } },
            ) { Text(stringResource(R.string.playback_speed_reset), color = VyoraText) }
        },
    )
}
