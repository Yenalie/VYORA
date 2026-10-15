package com.vyora.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.vyora.music.data.settings.ThemeMode

@Composable
fun resolveDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

private fun schemeFor(c: VyoraColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accent, onPrimary = Color.White,
        secondary = VyoraAccentSecondary, onSecondary = Color.White,
        tertiary = VyoraAqua,
        background = c.background, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceElevated, onSurfaceVariant = c.textMuted,
        surfaceContainerHigh = c.surfaceElevated,
        outline = c.divider,
        error = SystemColors.RedDark,
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = Color.White,
        secondary = VyoraAccentSecondary, onSecondary = Color.White,
        tertiary = VyoraAqua,
        background = c.background, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceSoft, onSurfaceVariant = c.textMuted,
        surfaceContainerHigh = c.surfaceElevated,
        outline = c.divider,
        error = SystemColors.Red,
    )
}

@Composable
fun VYORATheme(
    darkTheme: Boolean = true,
    accent: Color = Color(DefaultAccentArgb),
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme, accent) {
        if (darkTheme) darkVyoraColors(accent) else lightVyoraColors(accent)
    }
    CompositionLocalProvider(LocalVyoraColors provides colors) {
        MaterialTheme(colorScheme = schemeFor(colors), typography = Typography, content = content)
    }
}

@Composable
fun VyoraDarkScope(content: @Composable () -> Unit) {
    val current = LocalVyoraColors.current
    if (current.isDark) {
        content()
        return
    }
    val dark = remember(current.accent) { darkVyoraColors(current.accent) }
    CompositionLocalProvider(LocalVyoraColors provides dark) {
        MaterialTheme(colorScheme = schemeFor(dark), typography = Typography, content = content)
    }
}
