/**
 * VYORA floating navigation bar.
 *
 * Adapted from Convx's AppFloatingNavBar (Convx Project (C) 2026, GPL-3.0,
 * https://github.com/cosmictaserdev-creator/Convx). It drives the vendored
 * FloatingTabBar and glass engine unchanged; what differs is the wiring: VYORA has
 * no inline search-bar mode (its Search screen owns its own text field), so Search is
 * simply the standalone circle that navigates to the "search" route.
 */
@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.vyora.music.ui.glass

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyora.music.ui.glass.floatingtabbar.FloatingTabBar
import com.vyora.music.ui.glass.floatingtabbar.FloatingTabBarDefaults
import com.vyora.music.ui.glass.floatingtabbar.FloatingTabBarScrollConnection
import com.vyora.music.ui.glass.floatingtabbar.LocalTabBarBackdropFrozen
import com.vyora.music.ui.glass.shapes.ContinuousRoundedRectangle
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraSurfaceElevated

/** One destination in the bar. [route] doubles as the tab key. */
data class VyoraTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

// Kyant0/Capsule continuous (superellipse) capsule: smoother than a circular-arc pill.
private val NavBarShape = ContinuousRoundedRectangle(percent = 50)

// Width the expanded row needs for everything that is NOT a tab slot: the standalone
// circle plus the gap before it and the pill's own horizontal padding. (Convx value.)
private val NavBarStandaloneReserve = 80.dp

// Floor for a tab slot; below this icon and label stop reading as one target.
private val NavBarMinTabWidth = 56.dp

/**
 * @param tabs the tabs grouped in the pill (the puck travels across these).
 * @param standalone the circular tab beside the pill (Search), or null for none.
 * @param inlineAccessory / [expandedAccessory] slots for the mini player (phase 3);
 * null keeps the bar tabs-only.
 */
@Composable
fun VyoraFloatingNavBar(
    tabs: List<VyoraTab>,
    standalone: VyoraTab?,
    currentRoute: String?,
    onNavigate: (VyoraTab) -> Unit,
    scrollConnection: FloatingTabBarScrollConnection,
    modifier: Modifier = Modifier,
    inlineAccessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    expandedAccessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? = null,
    // Convenience over the two slots above: one composable used for both bar states.
    // The bar wraps it with the glass surface, so callers never deal with that.
    // [isInline] = true for the slim strip, false for the wide pill above the tabs.
    accessory: (@Composable (isInline: Boolean, contentColor: Color, modifier: Modifier) -> Unit)? = null,
) {
    val glassConfig = LocalGlassEffectConfig.current
    val useGlass = glassConfig.isEnabledFor(GlassComponent.NAV_BAR) && isGlassAllowed()

    // With glass the bar surface is the backdrop sample itself; without it (API < 31,
    // low-RAM devices, glass switched off) fall back to a solid elevated surface.
    val backgroundColor = if (useGlass) Color.Transparent else VyoraSurfaceElevated

    val tabTextColor by animateColorAsState(
        targetValue = glassConfig.textColor,
        animationSpec = tween(durationMillis = 200),
        label = "navBarTextColor",
    )
    // Selected tab glows in the accent; the puck's lens copy is tinted the same colour
    // (accentColor below) so the refracted icon under the puck matches the crisp one.
    val selectedContentColor = VyoraAccent
    val unselectedContentColor = tabTextColor.copy(alpha = 0.62f)

    // Deliberately NOT frozen while the bar folds. In the geometric bar the glass surfaces
    // really change size (pill <-> circle, mini player widening), and a frozen surface keeps the
    // capture it took at its OLD bounds: shrinking is fine, but growing back leaves the new area
    // with no capture at all, which read as broken glass when scrolling back up. The page behind
    // is already held by the scroll freeze, so each surface re-records from a layer that is
    // not changing — cheap. LocalTabBarBackdropFrozen is still honoured for any outer caller.
    val glassFrozen = LocalTabBarBackdropFrozen.current

    val tabBarContentModifier = if (useGlass) {
        Modifier.liquidGlass(
            config = glassConfig,
            shape = NavBarShape,
            highlightAlpha = 0.45f,
            frozen = glassFrozen,
        )
    } else {
        Modifier
    }

    // The mini player has its own switch in Settings > Liquid Glass: off gives it a solid
    // surface even while the tab bar stays glass.
    val miniGlass = glassConfig.isEnabledFor(GlassComponent.MINI_PLAYER) && isGlassAllowed()
    val accessoryGlassModifier = if (miniGlass) {
        Modifier.liquidGlass(
            config = glassConfig,
            shape = NavBarShape,
            highlightAlpha = 0.45f,
            frozen = glassFrozen,
        )
    } else {
        Modifier.background(VyoraSurfaceElevated, NavBarShape)
    }

    // Light tick when the tab actually changes (not when re-tapping the current one).
    val haptics = LocalHapticFeedback.current
    val navigate: (VyoraTab) -> Unit = { tab ->
        if (tab.route != currentRoute) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onNavigate(tab)
    }

    val resolvedInlineAccessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? =
        if (accessory != null) {
            { accessoryModifier, _ ->
                accessory(true, tabTextColor, accessoryModifier.then(accessoryGlassModifier))
            }
        } else inlineAccessory
    val resolvedExpandedAccessory: (@Composable SharedTransitionScope.(Modifier, AnimatedVisibilityScope) -> Unit)? =
        if (accessory != null) {
            { accessoryModifier, _ ->
                accessory(false, tabTextColor, accessoryModifier.fillMaxWidth().then(accessoryGlassModifier))
            }
        } else expandedAccessory

    // Sticky selection: on a destination that is not one of the pill's tabs (Search,
    // or a drilled-in playlist) the puck holds the last tab instead of snapping home.
    var stickyKey by remember { mutableStateOf(tabs.firstOrNull()?.route) }
    if (currentRoute != null && tabs.any { it.route == currentRoute }) stickyKey = currentRoute

    // Ink eases between accent and grey as the puck slides, instead of snapping. Animated
    // here because the tab builder below is not a composable scope.
    val inks = tabs.associate { item ->
        item.route to animateColorAsState(
            // While Search is open only the Search button is tinted; the puck stays put
            // (it shows where Back returns to) but its tab goes grey, as on iOS 26.
            if (item.route == stickyKey && (standalone == null || currentRoute != standalone.route)) selectedContentColor
            else unselectedContentColor,
            tween(220),
            label = "tabInk",
        )
    }
    val searchInk = animateColorAsState(
        if (standalone != null && currentRoute == standalone.route) selectedContentColor else unselectedContentColor,
        tween(220),
        label = "searchInk",
    )

    BoxWithConstraints(modifier) {
        val tabWidth = if (tabs.isEmpty()) {
            FloatingTabBarDefaults.TabWidth
        } else {
            ((maxWidth - NavBarStandaloneReserve) / tabs.size)
                .coerceIn(NavBarMinTabWidth, FloatingTabBarDefaults.TabWidth)
        }

        FloatingTabBar(
            selectedTabKey = stickyKey,
            scrollConnection = scrollConnection,
            modifier = Modifier.fillMaxWidth(),
            tabBarContentModifier = tabBarContentModifier,
            inlineAccessory = resolvedInlineAccessory,
            expandedAccessory = resolvedExpandedAccessory,
            colors = FloatingTabBarDefaults.colors(
                backgroundColor = backgroundColor,
                accessoryBackgroundColor = Color.Transparent,
            ),
            sizes = FloatingTabBarDefaults.sizes(
                tabBarContentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                tabExpandedContentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
                tabInlineContentPadding = PaddingValues(8.dp),
                tabWidth = tabWidth,
            ),
            // The puck's lens/accent preview only makes sense when the bar itself is glass.
            backdrop = if (useGlass) LocalAppBackdrop.current else null,
            // Must equal the crisp icon colour, or the lens-warped copy reads as a ghost.
            accentColor = selectedContentColor,
        ) {
            tabs.forEach { item ->
                val color = inks.getValue(item.route).value
                tab(
                    key = item.route,
                    title = {
                        Text(
                            text = item.label,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = color,
                            modifier = Modifier.size(25.dp),
                        )
                    },
                    onClick = { navigate(item) },
                )
            }

            if (standalone != null) {
                val color = searchInk.value
                standaloneTab(
                    key = standalone.route,
                    title = {
                        Text(
                            text = standalone.label,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = standalone.icon,
                            contentDescription = standalone.label,
                            tint = color,
                            modifier = Modifier.size(25.dp),
                        )
                    },
                    onClick = { navigate(standalone) },
                )
            }
        }
    }
}
