package com.vyora.music.ui.glass.floatingtabbar

import kotlin.math.roundToInt

/**
 * Every coordinate the fold needs, in pixels, relative to the bar's own top-left.
 */
internal data class FoldGeometry(
    val height: Int,
    val rowY: Int,
    /** Left edge shared by the nav surface, the resting tabs and the selected circle's row. */
    val spanX: Int,
    val navWidth: Int,
    val searchWidth: Int,
    val searchX: Int,
    val selectedX: Int,
    val playerX: Int,
    val playerY: Int,
    /** Width the mini player should be measured at. */
    val playerWidth: Int,
)

/**
 * Pure coordinates: reversing a fold retraces exactly the same geometry.
 *
 * VYORA's bar at rest is a centered cluster — the tab pill, a gap, and the search circle —
 * not one full-width pill (that is Square's layout, where search is a fifth tab inside it).
 * So the geometry runs on a "span" that is the cluster's width at rest and the whole bar's
 * width once folded:
 *
 *  - rest   (fold 0): span = cluster, centered. Pill on the left of it, circle on the right.
 *  - folded (fold 1): span = full width. Selected-tab circle left, search circle right, the
 *    mini player between them.
 *
 * The search circle is always the right edge of the span, which is why its x never needs the
 * tab count: the cluster is built so that `pill + spacing + circle == span` at rest.
 *
 * @param restPillWidth width of the tab pill at rest (tabs + its own padding)
 * @param selectedRestCenter x of the selected tab's centre inside the pill at rest
 */
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

    // The cluster can never be wider than the bar; if it would be, it simply fills it.
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
