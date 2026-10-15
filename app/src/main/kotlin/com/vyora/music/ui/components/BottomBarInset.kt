package com.vyora.music.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Height of the floating bottom UI (mini player + navigation pill), including the
 * system navigation-bar inset. Those bars are drawn on top of the screen content
 * rather than reserving space for it, so scrollable screens add this to their
 * bottom contentPadding. That lets the last row scroll fully clear of the bars
 * while everything above still passes visibly behind them.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }
