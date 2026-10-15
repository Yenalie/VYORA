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

data class VyoraTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val NavBarShape = ContinuousRoundedRectangle(percent = 50)

private val NavBarStandaloneReserve = 80.dp

private val NavBarMinTabWidth = 56.dp

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
    accessory: (@Composable (isInline: Boolean, contentColor: Color, modifier: Modifier) -> Unit)? = null,
) {
    val glassConfig = LocalGlassEffectConfig.current
    val useGlass = glassConfig.isEnabledFor(GlassComponent.NAV_BAR) && isGlassAllowed()

    val backgroundColor = if (useGlass) Color.Transparent else VyoraSurfaceElevated

    val tabTextColor by animateColorAsState(
        targetValue = glassConfig.textColor,
        animationSpec = tween(durationMillis = 200),
        label = "navBarTextColor",
    )
    val selectedContentColor = VyoraAccent
    val unselectedContentColor = tabTextColor.copy(alpha = 0.62f)

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

    var stickyKey by remember { mutableStateOf(tabs.firstOrNull()?.route) }
    if (currentRoute != null && tabs.any { it.route == currentRoute }) stickyKey = currentRoute

    val inks = tabs.associate { item ->
        item.route to animateColorAsState(
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
            backdrop = if (useGlass) LocalAppBackdrop.current else null,
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
