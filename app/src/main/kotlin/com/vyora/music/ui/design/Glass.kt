package com.vyora.music.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vyora.music.ui.glass.shapes.ContinuousCapsule
import com.vyora.music.ui.glass.shapes.ContinuousRoundedRectangle
import com.vyora.music.ui.theme.GlassFill
import com.vyora.music.ui.theme.GlassFillStrong
import com.vyora.music.ui.theme.GlassRimBottom
import com.vyora.music.ui.theme.GlassRimTop
import com.vyora.music.ui.theme.GlassSheen
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraOnAccent
import com.vyora.music.ui.theme.VyoraSeparator
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted

object GlassShapes {
    val Tiny = ContinuousRoundedRectangle(6.dp)
    val Small = ContinuousRoundedRectangle(10.dp)
    val Medium = ContinuousRoundedRectangle(14.dp)
    val Large = ContinuousRoundedRectangle(20.dp)
    val Pill = ContinuousCapsule()
}

object Spacing {
    val ScreenH = 20.dp
    val Section = 30.dp
    val RowArt = 48.dp
}

@Composable
fun Modifier.glass(
    shape: Shape = GlassShapes.Medium,
    fill: Color = GlassFill,
    rim: Boolean = true,
    sheen: Boolean = true,
): Modifier {
    val sheenColor = GlassSheen
    val rimTop = GlassRimTop
    val rimBottom = GlassRimBottom
    return this
    .clip(shape)
    .background(fill, shape)
    .then(
        if (sheen) Modifier.drawWithCache {
            val brush = Brush.verticalGradient(0f to sheenColor, 0.5f to Color.Transparent, startY = 0f, endY = size.height)
            onDrawBehind { drawRect(brush) }
        } else Modifier
    )
    .then(
        if (rim) Modifier.border(
            width = 0.8.dp,
            brush = Brush.linearGradient(
                colors = listOf(rimTop, rimBottom, rimBottom, rimTop.copy(alpha = rimTop.alpha * 0.4f)),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            ),
            shape = shape,
        ) else Modifier
    )
}

@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.glassClickable(
    pressedScale: Float = 0.97f,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    return this
        .pressScale(source, pressedScale)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            role = role,
            onLongClick = onLongClick?.let { cb -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); cb() } },
            onClick = onClick,
        )
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color = VyoraText,
    fill: Color = GlassFillStrong,
) {
    Box(
        modifier = modifier
            .size(size)
            .glassClickable(pressedScale = 0.88f, onClick = onClick)
            .liquidFill(shape = CircleShape, fill = fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    content: Color = VyoraAccent,
    fill: Color = GlassFillStrong,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .then(if (enabled) Modifier.glassClickable(onClick = onClick) else Modifier)
            .liquidFill(shape = GlassShapes.Pill, fill = fill)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) content else content.copy(alpha = 0.4f)
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val fill by animateColorAsState(if (selected) VyoraAccent.copy(alpha = 0.9f) else GlassFillStrong, tween(180), label = "chipFill")
    val content by animateColorAsState(if (selected) VyoraOnAccent else VyoraText, tween(180), label = "chipContent")
    Row(
        modifier = modifier
            .height(34.dp)
            .glassClickable(pressedScale = 0.94f, onClick = onClick)
            .liquidFill(shape = GlassShapes.Pill, fill = fill)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun LargeTitleHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow.uppercase(), color = VyoraTextMuted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(2.dp))
            }
            Text(title, color = VyoraText, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onAction != null && action == null) Modifier.glassClickable(pressedScale = 0.98f, onClick = onAction) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = action != null)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = VyoraText, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onAction != null && action == null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                        tint = VyoraTextMuted, modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (subtitle != null) {
                Text(subtitle, color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        if (action != null && onAction != null) {
            Text(
                action,
                color = VyoraAccent,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .glassClickable(onClick = onAction)
                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    Box(
        modifier
            .padding(start = startIndent)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(VyoraSeparator)
    )
}

@Composable
fun GlassEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = VyoraTextFaint, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = VyoraText, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Text(
                actionLabel,
                color = VyoraAccent,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.glassClickable(onClick = onAction).padding(8.dp),
            )
        }
    }
}
