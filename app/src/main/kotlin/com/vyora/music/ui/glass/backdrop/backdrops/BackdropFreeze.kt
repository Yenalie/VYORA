package com.vyora.music.ui.glass.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

private const val BackdropFreezeSafetyNs = 900_000_000L

private const val BackdropEntryFreezeWindowNs = 300_000_000L

class BackdropFreeze {
    private val scrollClockNs = longArrayOf(0L)

    private val entryClockNs = System.nanoTime()

    val frozen: () -> Boolean = {
        val now = System.nanoTime()
        if (now - entryClockNs < BackdropEntryFreezeWindowNs) {
            true
        } else {
            val started = scrollClockNs[0]
            started != 0L && now - started < BackdropFreezeSafetyNs
        }
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            scrollClockNs[0] = System.nanoTime()
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            scrollClockNs[0] = 0L
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberBackdropFreeze(): BackdropFreeze = remember { BackdropFreeze() }
