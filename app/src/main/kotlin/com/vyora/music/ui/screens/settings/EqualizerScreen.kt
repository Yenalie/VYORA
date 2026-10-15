package com.vyora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.playback.audio.AudioEffects
import com.vyora.music.playback.audio.EqBands
import com.vyora.music.playback.audio.EqPreset
import com.vyora.music.ui.design.GlassChip
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraSurfaceSoft
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AudioEffects.probe(context) }
    val settings by SettingsRepository.settings.collectAsState()
    val eq = settings.equalizer
    val caps by AudioEffects.capabilities.collectAsState()
    val probed by AudioEffects.probed.collectAsState()
    val inControl by AudioEffects.inControl.collectAsState()

    SettingsPage(title = stringResource(R.string.settings_equalizer), onBack = onBack) {
        item(key = "enable") {
            val footer = when {
                caps == null && probed -> stringResource(R.string.eq_unsupported)
                !eq.enabled -> stringResource(R.string.eq_disabled_hint)
                !inControl -> stringResource(R.string.eq_control_lost)
                else -> null
            }
            SettingsGroup(footer = footer) {
                SettingsSwitchRow(
                    title = stringResource(R.string.eq_enable),
                    checked = eq.enabled,
                    enabled = caps != null || !probed,
                    onCheckedChange = { v -> SettingsRepository.updateEqualizer { it.copy(enabled = v) } },
                    divider = false,
                )
            }
        }
        val c = caps ?: return@SettingsPage
        item(key = "presets") {
            Column(Modifier.padding(top = 22.dp)) {
                Text(
                    stringResource(R.string.eq_presets).uppercase(), color = VyoraTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(EqPreset.entries, key = { it.id }) { p ->
                        GlassChip(
                            label = presetLabel(p),
                            selected = eq.preset == p.id,
                            onClick = { SettingsRepository.updateEqualizer { it.copy(preset = p.id) } },
                        )
                    }
                    item(key = "custom") {
                        GlassChip(
                            label = stringResource(R.string.eq_custom),
                            selected = eq.preset == EqPreset.CUSTOM,
                            onClick = {
                                SettingsRepository.updateEqualizer {
                                    it.copy(preset = EqPreset.CUSTOM, customGains = EqBands.curve(it).toList())
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "bands") {
            SettingsGroup(header = stringResource(R.string.eq_bands)) {
                BandsEditor(gains = EqBands.curve(eq), dim = !eq.enabled) { band, db ->
                    SettingsRepository.updateEqualizer { s ->
                        val current = EqBands.curve(s).toMutableList()
                        current[band] = db
                        s.copy(preset = EqPreset.CUSTOM, customGains = current)
                    }
                }
            }
        }
        item(key = "effects") {
            SettingsGroup(header = stringResource(R.string.eq_effects), footer = stringResource(R.string.eq_footer)) {
                SettingsSliderRow(
                    title = stringResource(R.string.eq_bass_boost),
                    value = eq.bassBoost, valueRange = 0f..1f,
                    valueLabel = "${(eq.bassBoost * 100).roundToInt()}%",
                    onValueChange = { v -> SettingsRepository.updateEqualizer { it.copy(bassBoost = v) } },
                )
                SettingsSliderRow(
                    title = stringResource(R.string.eq_virtualizer),
                    value = eq.virtualizer, valueRange = 0f..1f,
                    valueLabel = "${(eq.virtualizer * 100).roundToInt()}%",
                    enabled = c.virtualizerSupported,
                    onValueChange = { v -> SettingsRepository.updateEqualizer { it.copy(virtualizer = v) } },
                )
                SettingsSliderRow(
                    title = stringResource(R.string.eq_loudness),
                    value = eq.loudness, valueRange = 0f..10f,
                    valueLabel = "+%.1f dB".format(eq.loudness),
                    onValueChange = { v -> SettingsRepository.updateEqualizer { it.copy(loudness = v) } },
                    divider = false,
                )
            }
        }
        item(key = "reset") {
            SettingsGroup {
                SettingsActionRow(
                    title = stringResource(R.string.reset_defaults),
                    onClick = {
                        SettingsRepository.updateEqualizer {
                            it.copy(preset = EqPreset.FLAT.id, customGains = emptyList(), bassBoost = 0f, virtualizer = 0f, loudness = 0f)
                        }
                    },
                    divider = false,
                )
            }
        }
    }
}

@Composable
private fun presetLabel(p: EqPreset) = stringResource(
    when (p) {
        EqPreset.FLAT -> R.string.eq_preset_flat
        EqPreset.BASS -> R.string.eq_preset_bass
        EqPreset.TREBLE -> R.string.eq_preset_treble
        EqPreset.VOCAL -> R.string.eq_preset_vocal
        EqPreset.POP -> R.string.eq_preset_pop
        EqPreset.ROCK -> R.string.eq_preset_rock
        EqPreset.JAZZ -> R.string.eq_preset_jazz
        EqPreset.CLASSICAL -> R.string.eq_preset_classical
        EqPreset.ELECTRONIC -> R.string.eq_preset_electronic
        EqPreset.HIPHOP -> R.string.eq_preset_hiphop
        EqPreset.ACOUSTIC -> R.string.eq_preset_acoustic
        EqPreset.LATE_NIGHT -> R.string.eq_preset_late_night
    }
)

private fun freqLabel(hz: Int) = if (hz >= 1000) {
    val k = hz / 1000f
    if (k >= 10f || abs(k - k.roundToInt()) < 0.05f) "${k.roundToInt()}k" else "%.1fk".format(k)
} else "$hz"

private fun dbLabel(db: Float): String {
    val r = (db * 2).roundToInt() / 2f
    val body = if (r == r.roundToInt().toFloat()) "${abs(r.roundToInt())}" else "%.1f".format(abs(r))
    return when {
        r > 0f -> "+$body"
        r < 0f -> "-$body"
        else -> "0"
    }
}

@Composable
private fun BandsEditor(gains: FloatArray, dim: Boolean, onChange: (Int, Float) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dim) 0.55f else 1f)
            .padding(horizontal = 4.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        EqBands.FREQUENCIES_HZ.forEachIndexed { band, hz ->
            val db = gains.getOrElse(band) { 0f }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    dbLabel(db),
                    color = VyoraText, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                VerticalSlider(
                    value = db, min = EqBands.MIN_DB, max = EqBands.MAX_DB,
                    onValueChange = { onChange(band, it) },
                    modifier = Modifier.height(190.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(freqLabel(hz), color = VyoraTextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun VerticalSlider(
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(onValueChange)
    val density = LocalDensity.current
    val knob = 22.dp
    val track = VyoraSurfaceSoft
    val accent = VyoraAccent
    BoxWithConstraints(modifier.width(28.dp)) {
        val knobPx = with(density) { knob.toPx() }
        val heightPx = constraints.maxHeight.toFloat()
        val usable = (heightPx - knobPx).coerceAtLeast(1f)
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val fraction = ((value - min) / span).coerceIn(0f, 1f)
        val zeroFraction = ((0f - min) / span).coerceIn(0f, 1f)
        fun emit(y: Float) {
            val f = (1f - (y - knobPx / 2f) / usable).coerceIn(0f, 1f)
            var v = min + f * span
            if (abs(v) < 0.4f) v = 0f
            latest((v * 2).roundToInt() / 2f)
        }
        val knobY = usable * (1f - fraction)
        val zeroY = usable * (1f - zeroFraction) + knobPx / 2f
        val fillTop = minOf(knobY + knobPx / 2f, zeroY)
        val fillHeight = abs((knobY + knobPx / 2f) - zeroY)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(usable) { detectTapGestures { emit(it.y) } }
                .pointerInput(usable) { detectVerticalDragGestures(onDragStart = { emit(it.y) }) { change, _ -> emit(change.position.y) } },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .padding(vertical = knob / 2)
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(track)
            )
            Box(
                Modifier
                    .offset { IntOffset(0, fillTop.roundToInt()) }
                    .width(4.dp)
                    .height(with(density) { fillHeight.toDp() })
                    .clip(CircleShape)
                    .background(accent)
            )
            Box(
                Modifier
                    .offset { IntOffset(0, (zeroY - with(density) { 1.dp.toPx() }).roundToInt()) }
                    .width(14.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(VyoraTextMuted.copy(alpha = 0.5f))
            )
            Box(
                Modifier
                    .offset { IntOffset(0, knobY.roundToInt()) }
                    .size(knob)
                    .shadow(4.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.08f), CircleShape)
            )
        }
    }
}
