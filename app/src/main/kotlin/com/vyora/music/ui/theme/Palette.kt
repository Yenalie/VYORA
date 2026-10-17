package com.vyora.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

object SystemColors {
    val Red = Color(0xFFFF3B30)
    val RedDark = Color(0xFFFF453A)
    val Orange = Color(0xFFFF9500)
    val YellowDark = Color(0xFFFFD60A)
    val Green = Color(0xFF34C759)
    val Teal = Color(0xFF30B0C7)
    val Blue = Color(0xFF007AFF)
    val BlueDark = Color(0xFF0A84FF)
    val Indigo = Color(0xFF5856D6)
    val Purple = Color(0xFFAF52DE)
    val Pink = Color(0xFFFF2D55)
    val Gray = Color(0xFF8E8E93)
}

val VyoraDestructive: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalVyoraColors.current.isDark) SystemColors.RedDark else SystemColors.Red

object ControlColors {
    val SegmentTrackDark = Color(0xFF2C2C2E)
    val SegmentTrackLight = Color(0x1F767680)
    val SegmentPillDark = Color(0xFF636366)
    val SegmentPillLight = Color.White

    val NavPuckLight = Color(0xFFDDDDE2)

    val PreviewBackdropDark = Color(0xFF101014)
    val PreviewBackdropLight = Color(0xFFF4F4F8)

    val AmbientFallback = Color(0xFF3A3A3C)
}

object MoodColors {
    val Chill = listOf(Color(0xFF5B7CFA), Color(0xFF6FD8E6))
    val Pop = listOf(Color(0xFFFF6FA5), Color(0xFFFFB36B))
    val Indonesian = listOf(Color(0xFFE5484D), Color(0xFFFF8F8F))
    val Workout = listOf(Color(0xFF8BD33A), Color(0xFF2FB99B))
    val Focus = listOf(Color(0xFF7A5CFF), Color(0xFFB08CFF))
    val Acoustic = listOf(Color(0xFFC88A4A), Color(0xFFE9C46A))
    val Party = listOf(Color(0xFFFF4D8D), Color(0xFF9B5CFF))
    val Sleep = listOf(Color(0xFF2C3E72), Color(0xFF5A6FB8))
    val HipHop = listOf(Color(0xFFF2A93B), Color(0xFFE5484D))
    val RnB = listOf(Color(0xFF9B2C6F), Color(0xFFE56BA8))
    val Rock = listOf(Color(0xFF3A3F47), Color(0xFF8A94A6))
    val KPop = listOf(Color(0xFF00B3A4), Color(0xFF6E8BFF))
}
