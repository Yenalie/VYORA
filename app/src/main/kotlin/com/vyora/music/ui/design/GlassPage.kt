package com.vyora.music.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vyora.music.ui.components.LocalMenuBackdrop
import com.vyora.music.ui.glass.LocalAppBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.layerBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.rememberLayerBackdrop

/**
 * A page with its own opaque background and glass that refracts that page, not whatever lies behind
 * the navigation host (the app-wide ambient layer).
 *
 * - Glass inside [content] (buttons in rows, chips) samples the page background.
 * - Glass in [overlay] (a floating back button, a pinned toolbar) samples the page *with* its
 *   scrolling content, so it bends the list passing underneath, as on iOS.
 */
@Composable
fun GlassPage(
    background: Color,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val pageBackdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
            Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop).background(background))
            CompositionLocalProvider(LocalAppBackdrop provides pageBackdrop) { content() }
        }
        CompositionLocalProvider(LocalAppBackdrop provides contentBackdrop, LocalMenuBackdrop provides contentBackdrop) {
            overlay()
        }
    }
}
