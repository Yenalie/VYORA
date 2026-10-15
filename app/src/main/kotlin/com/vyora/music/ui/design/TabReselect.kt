package com.vyora.music.ui.design

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Emits a tab's route when the user taps the tab that is already selected (iOS: scroll
 * that tab back to the top). Provided by MainActivity.
 */
val LocalTabReselect = staticCompositionLocalOf<SharedFlow<String>> { MutableSharedFlow() }

/** Runs [action] each time the [route] tab is tapped again while it is showing. */
@Composable
fun OnTabReselect(route: String, action: suspend () -> Unit) {
    val flow = LocalTabReselect.current
    val latest by rememberUpdatedState(action)
    LaunchedEffect(flow, route) {
        flow.collect { if (it == route) latest() }
    }
}

val LazyListState.isAtTop: Boolean
    get() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/**
 * iOS status-bar-tap style return to the top: from far down jump close first, so the
 * animated part is always short and quick instead of flinging through hundreds of rows.
 */
suspend fun LazyListState.scrollToTopSmooth() {
    if (firstVisibleItemIndex > 6) scrollToItem(6)
    animateScrollToItem(0)
}
