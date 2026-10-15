package com.vyora.music.ui.glass.backdrop.backdrops

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest

private const val NavTransitionFreezeWindowNs = 400_000_000L

class NavTransitionFreeze {
    private val startedAtNs = longArrayOf(0L)

    val frozen: () -> Boolean = {
        val started = startedAtNs[0]
        started != 0L && System.nanoTime() - started < NavTransitionFreezeWindowNs
    }

    fun markTransitionStarted() {
        startedAtNs[0] = System.nanoTime()
    }
}

@Composable
fun rememberNavTransitionFreeze(currentRoute: String?): NavTransitionFreeze {
    val freeze = remember { NavTransitionFreeze() }
    LaunchedEffect(currentRoute) {
        freeze.markTransitionStarted()
    }
    return freeze
}

@Composable
fun rememberPagerTransitionFreeze(pagerState: PagerState): NavTransitionFreeze {
    val freeze = remember { NavTransitionFreeze() }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collectLatest { scrolling ->
                while (scrolling) {
                    freeze.markTransitionStarted()
                    withFrameNanos { }
                }
            }
    }
    return freeze
}
