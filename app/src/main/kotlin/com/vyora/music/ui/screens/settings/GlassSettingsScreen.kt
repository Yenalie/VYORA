package com.vyora.music.ui.screens.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.data.settings.GlassLook
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.ui.design.liquidFill
import com.vyora.music.ui.glass.LocalAppBackdrop
import com.vyora.music.ui.glass.LocalGlassEffectConfig
import com.vyora.music.ui.glass.backdrop.backdrops.layerBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.vyora.music.ui.glass.isGlassAllowed
import com.vyora.music.ui.glass.liquidGlass
import com.vyora.music.ui.glass.shapes.ContinuousCapsule
import com.vyora.music.ui.theme.ControlColors
import com.vyora.music.ui.theme.GlassFillStrong
import com.vyora.music.ui.theme.LocalVyoraColors
import com.vyora.music.ui.theme.SystemColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraText
import kotlin.math.roundToInt

@Composable
fun GlassSettingsScreen(onBack: () -> Unit) {
    val settings by SettingsRepository.settings.collectAsState()
    val g = settings.glass
    val supported = isGlassAllowed()
    val on = g.enabled && supported
    val lensControls = on && g.style == GlassLook.LIQUID
    val blurControls = on && g.style != GlassLook.TINTED

    SettingsPage(title = stringResource(R.string.settings_liquid_glass), onBack = onBack) {
        item(key = "preview") {
            SettingsGroup(header = stringResource(R.string.glass_preview)) { GlassPreview() }
        }
        item(key = "master") {
            SettingsGroup(footer = stringResource(if (supported) R.string.glass_enable_footer else R.string.glass_unsupported)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.glass_enable),
                    checked = g.enabled,
                    enabled = supported,
                    onCheckedChange = { v -> SettingsRepository.updateGlass { it.copy(enabled = v) } },
                    divider = false,
                )
            }
        }
        item(key = "style") {
            SettingsGroup(header = stringResource(R.string.glass_style)) {
                val looks = GlassLook.entries
                looks.forEachIndexed { i, look ->
                    SettingsChoiceRow(
                        title = glassLookLabel(look),
                        subtitle = stringResource(
                            when (look) {
                                GlassLook.LIQUID -> R.string.glass_style_liquid_desc
                                GlassLook.FROSTED -> R.string.glass_style_frosted_desc
                                GlassLook.TINTED -> R.string.glass_style_tinted_desc
                            }
                        ),
                        selected = g.style == look,
                        enabled = on,
                        divider = i < looks.lastIndex,
                        onClick = { SettingsRepository.updateGlass { it.copy(style = look) } },
                    )
                }
            }
        }
        item(key = "effect") {
            SettingsGroup(header = stringResource(R.string.glass_effect)) {
                SettingsSliderRow(
                    title = stringResource(R.string.glass_blur),
                    value = g.blur, valueRange = 0f..12f,
                    valueLabel = "${g.blur.roundToInt()} dp",
                    enabled = blurControls,
                    onValueChange = { v -> SettingsRepository.updateGlass { it.copy(blur = v) } },
                )
                SettingsSliderRow(
                    title = stringResource(R.string.glass_refraction),
                    value = g.refraction, valueRange = 0f..1f,
                    valueLabel = percent(g.refraction),
                    enabled = lensControls,
                    onValueChange = { v -> SettingsRepository.updateGlass { it.copy(refraction = v) } },
                )
                SettingsSliderRow(
                    title = stringResource(R.string.glass_lens),
                    value = g.lensHeight, valueRange = 0f..1f,
                    valueLabel = percent(g.lensHeight),
                    enabled = lensControls,
                    onValueChange = { v -> SettingsRepository.updateGlass { it.copy(lensHeight = v) } },
                )
                SettingsSliderRow(
                    title = stringResource(R.string.glass_vibrancy),
                    value = g.vibrancy, valueRange = 0f..2f,
                    valueLabel = "%.1f×".format(g.vibrancy),
                    enabled = blurControls,
                    onValueChange = { v -> SettingsRepository.updateGlass { it.copy(vibrancy = v) } },
                )
                SettingsSliderRow(
                    title = stringResource(R.string.glass_highlight),
                    value = g.highlight, valueRange = 0f..1f,
                    valueLabel = percent(g.highlight),
                    enabled = lensControls,
                    onValueChange = { v -> SettingsRepository.updateGlass { it.copy(highlight = v) } },
                )
                SettingsSliderRow(
                    title = stringResource(R.string.glass_tint),
                    value = g.tint, valueRange = 0.1f..0.95f,
                    valueLabel = percent(g.tint),
                    enabled = g.enabled,
                    onValueChange = { v -> SettingsRepository.updateGlass { it.copy(tint = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.glass_chromatic),
                    checked = g.chromaticAberration, enabled = lensControls,
                    onCheckedChange = { v -> SettingsRepository.updateGlass { it.copy(chromaticAberration = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.glass_depth),
                    checked = g.depth, enabled = lensControls,
                    onCheckedChange = { v -> SettingsRepository.updateGlass { it.copy(depth = v) } },
                    divider = false,
                )
            }
        }
        item(key = "apply") {
            SettingsGroup(header = stringResource(R.string.glass_apply_to)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.glass_nav),
                    checked = g.navBar, enabled = on,
                    onCheckedChange = { v -> SettingsRepository.updateGlass { it.copy(navBar = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.glass_mini),
                    checked = g.miniPlayer, enabled = on,
                    onCheckedChange = { v -> SettingsRepository.updateGlass { it.copy(miniPlayer = v) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.glass_content),
                    checked = g.content, enabled = on,
                    onCheckedChange = { v -> SettingsRepository.updateGlass { it.copy(content = v) } },
                    divider = false,
                )
            }
        }
        item(key = "reset") {
            SettingsGroup {
                SettingsActionRow(
                    title = stringResource(R.string.reset_defaults),
                    onClick = { SettingsRepository.resetGlass() },
                    divider = false,
                )
            }
        }
    }
}

private fun percent(v: Float) = "${(v * 100).roundToInt()}%"

/**
 * Live preview: colourful drifting blobs recorded into their own backdrop layer, with a
 * miniature tab bar and a round glass button on top rendered by the real glass engine
 * and the current settings.
 */
@Composable
private fun GlassPreview() {
    val config = LocalGlassEffectConfig.current
    val colors = LocalVyoraColors.current
    val backdrop = rememberLayerBackdrop()
    val drift by rememberInfiniteTransition(label = "previewDrift").animateFloat(
        0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse), label = "drift",
    )
    val accent = VyoraAccent
    val live = config.globalEnabled && isGlassAllowed()

    Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(22.dp))) {
        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            drawRect(if (colors.isDark) ControlColors.PreviewBackdropDark else ControlColors.PreviewBackdropLight)
            fun blob(c: Color, x: Float, y: Float, r: Float) = drawCircle(
                Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = Offset(x, y), radius = r),
                radius = r, center = Offset(x, y),
            )
            val w = size.width; val h = size.height
            blob(accent, w * (0.15f + 0.35f * drift), h * 0.35f, h * 0.75f)
            blob(SystemColors.BlueDark, w * (0.85f - 0.3f * drift), h * 0.55f, h * 0.7f)
            blob(SystemColors.YellowDark, w * 0.5f, h * (0.9f - 0.4f * drift), h * 0.55f)
            // Stripes make refraction visible.
            for (i in 0..14) {
                val x = i * w / 14f
                drawLine(colors.text.copy(alpha = 0.10f), Offset(x, 0f), Offset(x - h * 0.4f, h), strokeWidth = 3f)
            }
        }
        CompositionLocalProvider(LocalAppBackdrop provides backdrop) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier.size(44.dp).liquidFill(shape = ContinuousCapsule(), fill = GlassFillStrong),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Pause, null, tint = VyoraText, modifier = Modifier.size(22.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val barShape = ContinuousCapsule()
                    Row(
                        Modifier
                            .weight(1f)
                            .height(58.dp)
                            .then(
                                if (live) Modifier.liquidGlass(config = config, shape = barShape, highlightAlpha = config.highlightOpacity)
                                else Modifier.clip(barShape).background(config.surfaceTintColor.copy(alpha = config.surfaceOpacity))
                            )
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        PreviewTab(Icons.Rounded.Home, stringResource(R.string.home), selected = true)
                        PreviewTab(Icons.Rounded.LibraryMusic, stringResource(R.string.library), selected = false)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .size(58.dp)
                            .then(
                                if (live) Modifier.liquidGlass(config = config, shape = CircleShape, highlightAlpha = config.highlightOpacity)
                                else Modifier.clip(CircleShape).background(config.surfaceTintColor.copy(alpha = config.surfaceOpacity))
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Search, null, tint = VyoraText, modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PreviewTab(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean) {
    val tint = if (selected) VyoraAccent else VyoraText
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
