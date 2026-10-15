@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.vyora.music.ui.glass.floatingtabbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

internal val LocalGeometricFloatingTabBar = staticCompositionLocalOf { false }
internal val LocalFloatingTabBarCollapseProgress = staticCompositionLocalOf<(() -> Float)?> { null }

@Composable
internal fun SharedTransitionScope.GeometricFloatingTabBar(
    scope: FloatingTabBarScopeImpl,
    selectedTabKey: Any?,
    isInline: Boolean,
    searchMode: Boolean,
    onExpand: () -> Unit,
    expandedTabs: @Composable SharedTransitionScope.(Modifier, androidx.compose.animation.AnimatedVisibilityScope) -> Unit,
    accessory: (@Composable SharedTransitionScope.(Modifier, androidx.compose.animation.AnimatedVisibilityScope) -> Unit)?,
    searchBarContent: (@Composable (Modifier) -> Unit)?,
    tabBarContentModifier: Modifier,
    colors: FloatingTabBarColors,
    shapes: FloatingTabBarShapes,
    sizes: FloatingTabBarSizes,
) {
    val foldRaw = animateFloatAsState(
        if (isInline) 1f else 0f,
        spring(dampingRatio = 0.9f, stiffness = 320f),
        label = "barGeometryFold",
    )
    val searchRaw = animateFloatAsState(
        if (searchMode) 1f else 0f,
        spring(dampingRatio = 0.9f, stiffness = 340f),
        label = "barGeometrySearch",
    )
    val fold = remember(foldRaw) { derivedStateOf { foldRaw.value.coerceIn(0f, 1f) } }
    val search = remember(searchRaw) { derivedStateOf { searchRaw.value.coerceIn(0f, 1f) } }
    val collapseProgress = remember(fold) { { fold.value } }
    val frozenWhileFolding = remember(fold, search) {
        {
            val f = fold.value
            val s = search.value
            (f > 0f && f < 1f) || (s > 0f && s < 1f)
        }
    }
    val selected = scope.getInlineTab(selectedTabKey)
    val searchTab = scope.standaloneTab ?: scope.tabs.lastOrNull()
    val selectedIndex = scope.tabs.indexOf(selected).coerceAtLeast(0)
    val tabsCount = scope.tabs.size.coerceAtLeast(1)
    val ownsSelectedSlot = selected?.key == selectedTabKey
    val sharedScope = this

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val tabWidthPx = with(density) { sizes.tabWidth.toPx() }
    val padStartPx = with(density) { sizes.tabBarContentPadding.calculateStartPadding(layoutDirection).toPx() }
    val padEndPx = with(density) { sizes.tabBarContentPadding.calculateEndPadding(layoutDirection).toPx() }
    val restPillWidth = (tabWidthPx * tabsCount + padStartPx + padEndPx).roundToInt()
    val selectedRestCenter = padStartPx + tabWidthPx * (selectedIndex + 0.5f)

    AnimatedVisibility(visible = true) {
        val visibility = this
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Box(Modifier
                    .background(colors.backgroundColor, shapes.tabBarShape)
                    .clip(shapes.tabBarShape).then(tabBarContentModifier))
                Box(Modifier.graphicsLayer {
                    alpha = (1f - fold.value * 3f).coerceIn(0f, 1f) * (1f - search.value)
                }.drawWithContent {
                    val p = fold.value
                    val width = lerp(lerp(size.width, size.height, search.value), size.height, p)
                    val contentScope = this
                    if (p == 0f && search.value == 0f) {
                        contentScope.drawContent()
                    } else {
                        val outline = Path().apply {
                            addRoundRect(RoundRect(0f, 0f, width, size.height, CornerRadius(size.height / 2f)))
                        }
                        clipPath(outline) { contentScope.drawContent() }
                    }
                }.then(if (isInline || searchMode) Modifier.clearAndSetSemantics {} else Modifier)) {
                    CompositionLocalProvider(
                        LocalGeometricFloatingTabBar provides true,
                        LocalTabBarBackdropFrozen provides frozenWhileFolding,
                    ) {
                        sharedScope.expandedTabs(Modifier, visibility)
                    }
                }
                Box(Modifier.graphicsLayer {
                    alpha = maxOf(fold.value, search.value)
                }.clip(shapes.standaloneTabShape).then(if (isInline || searchMode) Modifier.tapClickable(
                    indication = selected?.indication?.invoke(),
                    onClick = { if (isInline) onExpand() else selected?.onClick?.invoke() },
                ) else Modifier).then(if (!ownsSelectedSlot && !isInline && !searchMode) Modifier.clearAndSetSemantics {} else Modifier)) {
                    GeometricTabContent(
                        icon = { selected?.icon?.invoke() },
                        title = { selected?.title?.invoke() },
                        titleFraction = { (1f - fold.value) * (1f - search.value) },
                    )
                }
                Box(Modifier
                    .background(colors.backgroundColor, shapes.standaloneTabShape)
                    .clip(shapes.standaloneTabShape).then(tabBarContentModifier))
                Box(Modifier.graphicsLayer { alpha = 1f - search.value * (1f - fold.value) }
                    .clip(shapes.standaloneTabShape)
                    .tapClickable(
                        indication = searchTab?.indication?.invoke(),
                        onClick = { if (!searchMode || isInline) searchTab?.onClick?.invoke() },
                    )
                    .then(if (searchMode && !isInline) Modifier.clearAndSetSemantics {} else Modifier)) {
                    GeometricTabContent(
                        icon = { searchTab?.icon?.invoke() },
                        title = { searchTab?.title?.invoke() },
                        titleFraction = { 1f - fold.value },
                    )
                }
                Box(Modifier.graphicsLayer { alpha = search.value * (1f - fold.value) }
                    .then(if (!searchMode || isInline) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (searchMode || search.value > 0f) {
                        searchBarContent?.invoke(Modifier.fillMaxWidth().fillMaxHeight())
                    }
                }
                Box {
                    if (accessory != null) {
                        CompositionLocalProvider(LocalFloatingTabBarCollapseProgress provides collapseProgress) {
                            sharedScope.accessory(
                                Modifier.fillMaxWidth().background(colors.accessoryBackgroundColor, shapes.accessoryShape)
                                    .clip(shapes.accessoryShape), visibility,
                            )
                        }
                    }
                }
            },
        ) { children, constraints ->
            val width = constraints.maxWidth
            val spacing = sizes.componentSpacing.roundToPx()
            val p = fold.value
            val s = search.value

            val pillW = restPillWidth.coerceAtMost(width)
            val tabs = children[1].measure(Constraints.fixed(pillW, children[1].maxIntrinsicHeight(pillW)))
            val expandedHeight = tabs.height
            val compactHeight = FloatingTabBarInlineHeight.roundToPx().coerceAtMost(expandedHeight)
            val height = lerp(expandedHeight.toFloat(), compactHeight.toFloat(), p).roundToInt()
            val circle = height.coerceAtMost(width)

            val playerWidth0 = lerp(width.toFloat(), (width - 2 * (compactHeight + spacing)).coerceAtLeast(0).toFloat(), p).roundToInt()
            val playerHeight = if (accessory != null) children[6].maxIntrinsicHeight(playerWidth0) else 0
            val geometry = foldGeometry(
                width = width,
                restPillWidth = restPillWidth,
                expandedRowHeight = expandedHeight,
                collapsedRowHeight = compactHeight,
                spacing = spacing,
                playerHeight = playerHeight,
                hasPlayer = accessory != null,
                fold = p,
                search = s,
                selectedRestCenter = selectedRestCenter,
            )
            val player = children[6].measure(Constraints(minWidth = geometry.playerWidth, maxWidth = geometry.playerWidth))
            val surface = children[0].measure(Constraints.fixed(geometry.navWidth, height))
            val selectedButton = children[2].measure(Constraints.fixed(circle, height))
            val searchSurface = children[3].measure(Constraints.fixed(geometry.searchWidth, height))
            val searchControl = children[4].measure(Constraints.fixed(circle, height))
            val field = children[5].measure(Constraints.fixed(geometry.searchWidth, height))
            val rowY = geometry.rowY
            layout(width, geometry.height) {
                surface.placeRelative(geometry.spanX, rowY)
                tabs.placeRelative(geometry.spanX, rowY + (height - tabs.height) / 2)
                selectedButton.placeRelative(geometry.selectedX, rowY)
                searchSurface.placeRelative(geometry.searchX, rowY)
                searchControl.placeRelative(geometry.searchX, rowY)
                field.placeRelative(geometry.searchX, rowY)
                player.placeRelative(geometry.playerX, geometry.playerY)
            }
        }
    }
}

@Composable
private fun GeometricTabContent(
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    titleFraction: () -> Float,
) {
    Layout(content = {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { icon() }
        Box(Modifier.graphicsLayer { alpha = titleFraction() }) { title() }
    }) { children, constraints ->
        val iconPlaceable = children[0].measure(Constraints.fixed(26.dp.roundToPx(), 26.dp.roundToPx()))
        val titlePlaceable = children[1].measure(Constraints(maxWidth = constraints.maxWidth))
        val gap = 2.dp.roundToPx()
        val fullTop = (constraints.maxHeight - iconPlaceable.height - gap - titlePlaceable.height) / 2f
        val top = lerp((constraints.maxHeight - iconPlaceable.height) / 2f, fullTop, titleFraction()).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            iconPlaceable.placeRelative((constraints.maxWidth - iconPlaceable.width) / 2, top)
            titlePlaceable.placeRelative((constraints.maxWidth - titlePlaceable.width) / 2, top + iconPlaceable.height + gap)
        }
    }
}
