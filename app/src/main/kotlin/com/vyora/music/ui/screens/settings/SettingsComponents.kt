package com.vyora.music.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vyora.music.R
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.Hairline
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.theme.ControlColors
import com.vyora.music.ui.theme.LocalVyoraColors
import com.vyora.music.ui.theme.SystemColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraDestructive
import com.vyora.music.ui.theme.VyoraGroupedBackground
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraHighlight
import com.vyora.music.ui.theme.VyoraSurfaceSoft
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlin.math.roundToInt

private val CellShape = RoundedCornerShape(22.dp)
private val RowHorizontal = 16.dp

@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val bottomInset = LocalBottomBarInset.current
    Box(modifier.fillMaxSize().background(VyoraGroupedBackground)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 36.dp + bottomInset),
        ) {
            item(key = "settings_top") {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                    if (onBack != null) {
                        GlassIconButton(
                            Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack,
                            size = 44.dp, iconSize = 20.dp,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    LargeTitleHeader(title = title, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            content()
        }
    }
}

@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = if (header != null) 22.dp else 26.dp)) {
        if (header != null) {
            Text(
                header.uppercase(),
                color = VyoraTextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = RowHorizontal, end = RowHorizontal, bottom = 7.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CellShape)
                .background(VyoraGroupedCell),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                color = VyoraTextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = RowHorizontal, end = RowHorizontal, top = 7.dp),
            )
        }
    }
}

@Composable
fun SettingsIcon(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = SystemColors.Gray,
    subtitle: String? = null,
    titleColor: Color = VyoraText,
    enabled: Boolean = true,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed && onClick != null) VyoraHighlight else Color.Transparent,
        tween(if (pressed) 0 else 220), label = "settingsRowBg",
    )
    Box(Modifier.fillMaxWidth().background(bg)) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(
                    if (onClick != null && enabled) Modifier.clickable(
                        interactionSource = source, indication = null, role = Role.Button, onClick = onClick,
                    ) else Modifier
                )
                .alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = RowHorizontal, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                SettingsIcon(icon, iconTint)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, color = titleColor, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        SettingsBadge(badge)
                    }
                }
                if (subtitle != null) {
                    Text(subtitle, color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
        if (divider) {
            Hairline(Modifier.align(Alignment.BottomStart), startIndent = if (icon != null) 60.dp else RowHorizontal)
        }
    }
}

/** Small capsule next to a row title ("In use", "Connected"), like iOS's status tags. */
@Composable
fun SettingsBadge(text: String, color: Color = VyoraAccent) {
    Text(
        text.uppercase(),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    iconTint: Color = SystemColors.Gray,
    value: String? = null,
    subtitle: String? = null,
    divider: Boolean = true,
    badge: String? = null,
) {
    SettingsRow(
        title = title, icon = icon, iconTint = iconTint, subtitle = subtitle, divider = divider, onClick = onClick, badge = badge,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(value, color = VyoraTextMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = VyoraTextFaint, modifier = Modifier.size(22.dp))
            }
        },
    )
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconTint: Color = SystemColors.Gray,
    subtitle: String? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
    badge: String? = null,
) {
    SettingsRow(
        title = title, icon = icon, iconTint = iconTint, subtitle = subtitle, enabled = enabled, divider = divider,
        badge = badge,
        onClick = { onCheckedChange(!checked) },
        trailing = { IosSwitch(checked = checked, onCheckedChange = if (enabled) onCheckedChange else null) },
    )
}

@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    SettingsRow(
        title = title, subtitle = subtitle, enabled = enabled, divider = divider, onClick = onClick,
        trailing = {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, null, tint = VyoraAccent, modifier = Modifier.size(20.dp))
            }
        },
    )
}

@Composable
fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = SystemColors.Gray,
    divider: Boolean = true,
) {
    SettingsRow(
        title = title,
        icon = icon,
        iconTint = iconTint,
        titleColor = if (destructive) VyoraDestructive else VyoraAccent,
        divider = divider,
        onClick = onClick,
    )
}

@Composable
fun SettingsSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    enabled: Boolean = true,
    divider: Boolean = true,
    steps: Int = 0,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = RowHorizontal, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = VyoraText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(valueLabel, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            IosSlider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled, steps = steps)
        }
        if (divider) Hairline(Modifier.align(Alignment.BottomStart), startIndent = RowHorizontal)
    }
}

@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val off = VyoraSurfaceSoft
    val track by animateColorAsState(if (checked) SystemColors.Green else off, tween(200), label = "switchTrack")
    val knobX by animateDpAsState(
        if (checked) 22.dp else 2.dp,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "switchKnob",
    )
    Box(
        modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(track)
            .then(
                if (onCheckedChange != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(!checked)
                } else Modifier
            ),
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 2.dp)
                .size(27.dp)
                .shadow(3.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color.White)
                .border(0.5.dp, Color.Black.copy(alpha = 0.06f), CircleShape)
        )
    }
}

@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val latestChange by rememberUpdatedState(onValueChange)
    val knob = 28.dp
    val density = LocalDensity.current
    val inactive = VyoraSurfaceSoft
    val accent = VyoraAccent

    BoxWithConstraints(modifier.fillMaxWidth().height(32.dp)) {
        val knobPx = with(density) { knob.toPx() }
        val widthPx = constraints.maxWidth.toFloat()
        val usable = (widthPx - knobPx).coerceAtLeast(1f)
        fun emit(x: Float) {
            var f = ((x - knobPx / 2f) / usable).coerceIn(0f, 1f)
            if (steps > 0) f = (f * (steps + 1)).roundToInt() / (steps + 1).toFloat()
            latestChange(valueRange.start + f * span)
        }
        val gestures = if (enabled) Modifier
            .pointerInput(usable) { detectTapGestures { emit(it.x) } }
            .pointerInput(usable) { detectDragGestures(onDragStart = { emit(it.x) }) { change, _ -> emit(change.position.x) } }
        else Modifier
        Box(Modifier.fillMaxSize().then(gestures), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .padding(horizontal = knob / 2)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(inactive)
            )
            Box(
                Modifier
                    .padding(start = knob / 2)
                    .width(with(density) { (usable * fraction).toDp() })
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Box(
                Modifier
                    .offset { IntOffset((usable * fraction).roundToInt(), 0) }
                    .size(knob)
                    .shadow(4.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.08f), CircleShape)
            )
        }
    }
}

@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val dark = LocalVyoraColors.current.isDark
    val trackColor = if (dark) ControlColors.SegmentTrackDark else ControlColors.SegmentTrackLight
    val pill = if (dark) ControlColors.SegmentPillDark else ControlColors.SegmentPillLight
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(10.dp))
            .background(trackColor)
            .padding(2.dp)
    ) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(segment * index, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium), label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(segment)
                .fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(8.dp), clip = false)
                .clip(RoundedCornerShape(8.dp))
                .background(pill)
                .border(0.5.dp, Color.Black.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
            options.forEach { option ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null, role = Role.Tab,
                        ) {
                            if (option != selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(option)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        color = VyoraText,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsControlRow(divider: Boolean = true, title: String? = null, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = RowHorizontal, vertical = 12.dp)) {
            if (title != null) {
                Text(title, color = VyoraText, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
        if (divider) Hairline(Modifier.align(Alignment.BottomStart), startIndent = RowHorizontal)
    }
}
