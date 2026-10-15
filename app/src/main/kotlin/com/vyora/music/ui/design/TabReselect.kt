package com.vyora.music.ui.design

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

val LocalTabReselect = staticCompositionLocalOf<SharedFlow<String>> { MutableSharedFlow() }

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

suspend fun LazyListState.scrollToTopSmooth() {
    if (firstVisibleItemIndex > 6) scrollToItem(6)
    animateScrollToItem(0)
}
