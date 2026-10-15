package com.vyora.music.ui.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp

/**
 * Shared-element plumbing for the "artwork flies from the list into the album page" move.
 * Both are provided by MainActivity: the layout around the NavHost and the animated scope
 * of the destination currently being composed. Outside of them [sharedArtwork] is a no-op,
 * so the same composables still work in previews, menus and the player.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Same key on the list row and the page header = the cover morphs between them. */
fun artworkKey(id: String) = "artwork-$id"

/** Slightly underdamped like iOS: the cover lands with a hint of settle, no wobble. */
@OptIn(ExperimentalSharedTransitionApi::class)
private val ArtworkBounds = BoundsTransform { _: Rect, _: Rect ->
    spring(dampingRatio = 0.86f, stiffness = 360f)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArtwork(key: String?): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val scope = LocalNavAnimatedScope.current ?: return this
    if (key == null) return this
    return with(shared) {
        this@sharedArtwork.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = scope,
            boundsTransform = ArtworkBounds,
        )
    }
}

/**
 * Corner radius that starts at [from] (the list thumbnail) and eases to [to] while this
 * destination enters, and back on exit, so the square cover's corners morph mid-flight.
 */
@Composable
fun morphingCorner(from: Dp, to: Dp): State<Dp> {
    val scope = LocalNavAnimatedScope.current ?: return rememberUpdatedState(to)
    return scope.transition.animateDp(
        transitionSpec = { tween(320) },
        label = "artworkCorner",
    ) { state -> if (state == EnterExitState.Visible) to else from }
}
