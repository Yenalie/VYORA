package com.vyora.music.ui.glass.floatingtabbar

import kotlin.math.roundToInt

internal data class FoldGeometry(
    val height: Int,
    val rowY: Int,
    val spanX: Int,
    val navWidth: Int,
    val searchWidth: Int,
    val searchX: Int,
    val selectedX: Int,
    val playerX: Int,
    val playerY: Int,
    val playerWidth: Int,
)

internal fun foldGeometry(
    width: Int,
    restPillWidth: Int,
    expandedRowHeight: Int,
    collapsedRowHeight: Int,
    spacing: Int,
    playerHeight: Int,
    hasPlayer: Boolean,
    fold: Float,
    search: Float,
    selectedRestCenter: Float,
): FoldGeometry {
    fun mix(a: Float, b: Float, progress: Float) = a + (b - a) * progress
    val p = fold.coerceIn(0f, 1f)
    val s = search.coerceIn(0f, 1f)

    val rowHeight = mix(expandedRowHeight.toFloat(), collapsedRowHeight.toFloat(), p).roundToInt()
    val circle = rowHeight.coerceAtMost(width)
    val rowY = if (hasPlayer) ((playerHeight + spacing) * (1f - p)).roundToInt() else 0

    val restCluster = (restPillWidth + spacing + expandedRowHeight).coerceAtMost(width)
    val restPill = (restCluster - spacing - expandedRowHeight).coerceAtLeast(circle)
    val spanW = mix(restCluster.toFloat(), width.toFloat(), p)
    val spanX = mix((width - restCluster) / 2f, 0f, p)

    val searchWidth = mix(circle.toFloat(), (spanW - circle - spacing).coerceAtLeast(circle.toFloat()), s * (1f - p))
    val navWidth = mix(mix(restPill.toFloat(), circle.toFloat(), s), circle.toFloat(), p)

    return FoldGeometry(
        height = rowHeight + rowY,
        rowY = rowY,
        spanX = spanX.roundToInt(),
        navWidth = navWidth.roundToInt(),
        searchWidth = searchWidth.roundToInt(),
        searchX = (spanX + spanW - searchWidth).roundToInt(),
        selectedX = (spanX + mix(selectedRestCenter - circle / 2f, 0f, maxOf(p, s))).roundToInt(),
        playerX = (spanX + mix(0f, (circle + spacing).toFloat(), p)).roundToInt(),
        playerY = (rowY - mix((playerHeight + spacing).toFloat(), (playerHeight - rowHeight) / 2f, p)).roundToInt(),
        playerWidth = (spanW - p * 2f * (circle + spacing)).coerceAtLeast(0f).roundToInt(),
    )
}
