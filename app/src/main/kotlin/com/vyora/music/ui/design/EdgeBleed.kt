package com.vyora.music.ui.design

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lets a horizontally scrolling row inside a padded column reach the screen edges, the way Apple Music's
 * chip rows do: the row is laid out [horizontal] wider on each side, so content scrolls out under the edge of
 * the screen instead of being cut off in a hard vertical line at the column's padding. Pair it with
 * `contentPadding = PaddingValues(horizontal = horizontal)` so the first item still lines up with the column.
 */
fun Modifier.bleedHorizontal(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val wide = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    } else constraints
    val placeable = measurable.measure(wide)
    val width = if (constraints.hasBoundedWidth) (placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth) else placeable.width
    layout(width, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * A short alpha fade on whichever end still has content to scroll to, so items slide in and out softly
 * rather than being sliced by the screen edge. Ends that are already at rest stay fully opaque.
 */
fun Modifier.scrollEdgeFade(state: ScrollableState, length: Dp = 18.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val l = length.toPx().coerceAtMost(size.width / 3f)
        if (l <= 0f) return@drawWithContent
        if (state.canScrollBackward) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = l),
                size = androidx.compose.ui.geometry.Size(l, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - l, endX = size.width),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - l, 0f),
                size = androidx.compose.ui.geometry.Size(l, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
