package com.vyora.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.withFrameNanos
import com.vyora.music.data.lyrics.LyricClock
import com.vyora.music.data.lyrics.LyricLine
import com.vyora.music.playback.LyricPlaybackPosition
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

/**
 * Port of BitChord's `rememberLyricClock` and `SweptLyricLine` (GPL-3.0,
 * upstream commit edc1e6f36febf71a118e649411d2fc21b70ead45).
 * The clock runs on display frames and is steered by dated player samples;
 * lyric text stays laid out once while only a draw-phase clipping edge moves.
 */
@Composable
internal fun rememberBitChordLyricClock(
    trackKey: Any,
    playhead: State<LyricPlaybackPosition>,
    isPlaying: Boolean,
    enabled: Boolean = true,
): MutableLongState {
    val clock = remember(trackKey) {
        mutableLongStateOf(Snapshot.withoutReadObservation { playhead.value.positionMs })
    }
    val engine = remember(trackKey) { LyricClock(clock.longValue) }

    if (!isPlaying && enabled) {
        LaunchedEffect(engine, playhead) {
            snapshotFlow { playhead.value }.collect { reading ->
                engine.hold(reading.positionMs, nanosToMs(System.nanoTime()))
                clock.longValue = reading.positionMs
            }
        }
    }

    val foreground = rememberIsForeground()
    LaunchedEffect(engine, playhead, isPlaying, foreground, enabled) {
        if (!isPlaying || !foreground || !enabled) return@LaunchedEffect
        engine.restart()
        while (true) {
            withFrameNanos { frameNanos ->
                val system = System.nanoTime()
                val now = if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system
                val reading = playhead.value
                clock.longValue = engine.frame(
                    nowMs = nanosToMs(now),
                    reportedMs = reading.positionMs,
                    sampledAtMs = if (reading.sampledAtNanos > 0L) nanosToMs(reading.sampledAtNanos) else Double.NaN,
                    discontinuity = reading.discontinuity,
                )
            }
        }
    }
    return clock
}

/** BitChord's foreground gate: stop requesting display frames when VYORA is not resumed. */
@Composable
private fun rememberIsForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        androidx.compose.runtime.mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0
private const val SAME_CLOCK_NANOS = 250_000_000L

/** Same two-layer, draw-phase sweep as BitChord; no AnimatedString construction per frame. */
@Composable
internal fun BitChordSweptLyricLine(
    line: LyricLine,
    clock: State<Long>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    dimAlpha: Float = 0.55f,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    var layout by remember(line) { androidx.compose.runtime.mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier) {
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
        )
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = Modifier.drawWithContent {
                val measured = layout
                if (measured == null || line.text.isEmpty()) {
                    drawContent()
                } else {
                    sweepTo(measured, line.revealedChars(clock.value), line.sweepSpans)
                }
            },
        )
    }
}

/** BitChord's visible-line-aware hit coordinate, including wraps and RTL runs. */
private fun TextLayoutResult.xOn(offset: Int, visualLine: Int): Float {
    val lineStart = getLineStart(visualLine)
    val lineEndVisible = getLineEnd(visualLine, visibleEnd = true)
    val lineLeft = getLineLeft(visualLine)
    val lineRight = getLineRight(visualLine)
    val rtl = getParagraphDirection(lineStart.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) ==
        ResolvedTextDirection.Rtl
    return when {
        offset <= lineStart -> if (rtl) lineRight else lineLeft
        offset >= lineEndVisible -> if (rtl) lineLeft else lineRight
        else -> getHorizontalPosition(offset, usePrimaryDirection = true).coerceIn(lineLeft, lineRight)
    }
}

/** Interpolate across a timed word/syllable at constant width, not character-by-character. */
private fun horizontalAt(layout: TextLayoutResult, chars: Float, visualLine: Int, spans: List<IntRange>): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
    val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
    val timedPiece = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (timedPiece != null && timedPiece.first >= lineStart && timedPiece.last + 1 <= lineEndVisible) {
        val from = layout.xOn(timedPiece.first, visualLine)
        val to = layout.xOn(timedPiece.last + 1, visualLine)
        return from + (to - from) * (chars - timedPiece.first) / (timedPiece.last + 1 - timedPiece.first)
    }
    val index = chars.toInt().coerceIn(lineStart, lineEndAll)
    val here = layout.xOn(index, visualLine)
    val rawNext = index + 1
    val textLength = layout.layoutInput.text.length
    val spills = layout.getLineForOffset(rawNext.coerceIn(0, textLength)) != visualLine
    val next = if (spills) {
        if (rawNext > lineEndVisible) {
            val rtl = layout.getParagraphDirection(visualLine.coerceAtMost(lineEndAll).coerceIn(0, textLength - 1)) ==
                ResolvedTextDirection.Rtl
            if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
        } else {
            layout.xOn(rawNext.coerceAtMost(lineEndVisible), visualLine)
        }
    } else {
        layout.xOn(rawNext, visualLine)
    }
    return here + (next - here) * (chars - index)
}

/** BitChord's row-aware clip mask: completed rows light fully and the current row is clipped at its timed edge. */
private fun ContentDrawScope.sweepTo(layout: TextLayoutResult, revealedChars: Float, spans: List<IntRange>) {
    if (revealedChars <= 0f) return
    val textLength = layout.layoutInput.text.length
    if (revealedChars >= textLength) {
        drawContent()
        return
    }
    val directionOffset = layout.getLineStart(0).coerceIn(0, (textLength - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(directionOffset) == ResolvedTextDirection.Rtl
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
        if (revealedChars <= lineStart) return
        val charPosition = if (revealedChars < lineEndAll) {
            horizontalAt(layout, revealedChars, visualLine, spans)
        } else {
            if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
        }
        val lineLeft = layout.getLineLeft(visualLine)
        val lineRight = layout.getLineRight(visualLine)
        val safePosition = charPosition.coerceIn(lineLeft, lineRight)
        val (clipLeft, clipRight) = if (rtl) safePosition to lineRight else lineLeft to safePosition
        if (clipLeft >= clipRight) continue
        clipRect(
            left = clipLeft,
            top = layout.getLineTop(visualLine),
            right = clipRight,
            bottom = layout.getLineBottom(visualLine),
        ) { this@sweepTo.drawContent() }
    }
}
