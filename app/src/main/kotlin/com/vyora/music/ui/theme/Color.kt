package com.vyora.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class VyoraColors(
    val isDark: Boolean,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceSoft: Color,
    val groupedBackground: Color,
    val groupedCell: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val divider: Color,
    val separator: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassFillPressed: Color,
    val glassRimTop: Color,
    val glassRimBottom: Color,
    val glassSheen: Color,
    val chromeTint: Color,
    val scrim: Color,
    val highlight: Color,
)

const val DefaultAccentArgb = 0xFFFA2D48

fun darkVyoraColors(accent: Color = Color(DefaultAccentArgb)) = VyoraColors(
    isDark = true,
    accent = accent,
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceElevated = Color(0xFF2C2C2E),
    surfaceSoft = Color(0xFF3A3A3C),
    groupedBackground = Color(0xFF000000),
    groupedCell = Color(0xFF1C1C1E),
    text = Color(0xFFFFFFFF),
    textMuted = Color(0x99EBEBF5),
    textFaint = Color(0x4DEBEBF5),
    divider = Color(0xFF38383A),
    separator = Color(0x5C545458),
    glassFill = Color.White.copy(alpha = 0.08f),
    glassFillStrong = Color.White.copy(alpha = 0.14f),
    glassFillPressed = Color.White.copy(alpha = 0.20f),
    glassRimTop = Color.White.copy(alpha = 0.28f),
    glassRimBottom = Color.White.copy(alpha = 0.04f),
    glassSheen = Color.White.copy(alpha = 0.07f),
    chromeTint = Color(0xFF0E0E0E),
    scrim = Color.Black.copy(alpha = 0.32f),
    highlight = Color.White.copy(alpha = 0.10f),
)

fun lightVyoraColors(accent: Color = Color(DefaultAccentArgb)) = VyoraColors(
    isDark = false,
    accent = accent,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F7),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceSoft = Color(0xFFE5E5EA),
    groupedBackground = Color(0xFFF2F2F7),
    groupedCell = Color(0xFFFFFFFF),
    text = Color(0xFF000000),
    textMuted = Color(0x993C3C43),
    textFaint = Color(0x4D3C3C43),
    divider = Color(0xFFC6C6C8),
    separator = Color(0x4A3C3C43),
    glassFill = Color(0x14767680),
    glassFillStrong = Color(0x24767680),
    glassFillPressed = Color(0x38767680),
    glassRimTop = Color.White.copy(alpha = 0.85f),
    glassRimBottom = Color.Black.copy(alpha = 0.06f),
    glassSheen = Color.White.copy(alpha = 0.35f),
    chromeTint = Color(0xFFF7F7F9),
    scrim = Color.Black.copy(alpha = 0.18f),
    highlight = Color.Black.copy(alpha = 0.06f),
)

val LocalVyoraColors = staticCompositionLocalOf { darkVyoraColors() }

val VyoraAccent: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.accent
val VyoraOnAccent: Color get() = Color.White
val VyoraBackground: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.background
val VyoraSurface: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.surface
val VyoraSurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.surfaceElevated
val VyoraSurfaceSoft: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.surfaceSoft
val VyoraGroupedBackground: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.groupedBackground
val VyoraGroupedCell: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.groupedCell
val VyoraText: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.text
val VyoraTextMuted: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.textMuted
val VyoraTextFaint: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.textFaint
val VyoraSeparator: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.separator
val VyoraHighlight: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.highlight
val VyoraScrim: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.scrim
val VyoraAqua = Color(0xFF64D2FF)
val VyoraAccentSecondary = Color(0xFFFF6482)

val GlassFill: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.glassFill
val GlassFillStrong: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.glassFillStrong
val GlassFillPressed: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.glassFillPressed
val GlassRimTop: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.glassRimTop
val GlassRimBottom: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.glassRimBottom
val GlassSheen: Color @Composable @ReadOnlyComposable get() = LocalVyoraColors.current.glassSheen
