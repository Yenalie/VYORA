package com.vyora.music.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyora.music.ui.theme.LocalVyoraColors
import com.vyora.music.ui.theme.VyoraAccent

/**
 * The bottom chrome with Liquid Glass off, modelled on Apple Music for Android: an opaque Material 3
 * navigation bar with the mini player docked straight on top of it, both edge to edge and split by a
 * hairline. Selected tabs are drawn in the accent colour with no pill indicator, as Apple does it.
 */
@Composable
fun VyoraDockedNavBar(
    tabs: List<VyoraTab>,
    currentRoute: String?,
    onNavigate: (VyoraTab) -> Unit,
    modifier: Modifier = Modifier,
    miniPlayer: (@Composable (contentColor: Color, modifier: Modifier) -> Unit)? = null,
) {
    val colors = LocalVyoraColors.current
    // A touch lifted from the page so the bar reads as chrome, never see-through.
    val barColor = if (colors.isDark) Color(0xFF161618) else Color(0xFFF9F9F9)
    val haptics = LocalHapticFeedback.current

    var stickyKey by remember { mutableStateOf(tabs.firstOrNull()?.route) }
    if (currentRoute != null && tabs.any { it.route == currentRoute }) stickyKey = currentRoute

    Column(modifier.fillMaxWidth().background(barColor)) {
        if (miniPlayer != null) {
            HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
            miniPlayer(colors.text, Modifier.fillMaxWidth().height(DockedMiniHeight))
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(DockedBarHeight)
                .selectableGroup(),
        ) {
            tabs.forEach { tab ->
                val selected = tab.route == stickyKey
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onNavigate(tab)
                    },
                    icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = {
                        Text(
                            tab.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VyoraAccent,
                        selectedTextColor = VyoraAccent,
                        unselectedIconColor = colors.textMuted,
                        unselectedTextColor = colors.textMuted,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

private val DockedBarHeight = 60.dp
private val DockedMiniHeight = 64.dp
