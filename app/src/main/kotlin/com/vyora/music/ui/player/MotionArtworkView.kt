package com.vyora.music.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import com.vyora.music.data.motion.MotionArt
import com.vyora.music.data.motion.MotionArtwork
import com.vyora.music.data.settings.VideoArtwork
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.playback.video.MotionLoopPlayer
import com.vyora.music.playback.video.VideoArtworkPlayer
import kotlin.math.roundToInt

/**
 * Motion artwork for [item] when the song has one and the setting allows it; null while looking it up, when
 * there is none, or when the setting is off. Lookups are cached, so revisiting a song answers instantly.
 */
@Composable
fun rememberMotionArtwork(item: SimpleItem?, mode: VideoArtwork): MotionArt? {
    val context = LocalContext.current
    val allowed = item != null && !item.isVideo && VideoArtworkPlayer.allowed(context, mode)
    val key = item?.let { it.title + "\u0000" + it.artist }
    val art by produceState<MotionArt?>(initialValue = item?.takeIf { allowed }?.let { MotionArtwork.cached(it)?.getOrNull() }, key, allowed) {
        value = item?.takeIf { allowed }?.let { MotionArtwork.cached(it)?.getOrNull() }
        if (item == null || !allowed || value != null) return@produceState
        MotionArtwork.init(context)
        value = runCatching { MotionArtwork.find(item, metered(context)) }.getOrNull()
    }
    return art
}

private fun metered(context: Context): Boolean =
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: true

/**
 * Plays [art] as a silent loop, cropped to fill this composable's bounds (center crop, like
 * `ContentScale.Crop`). Fades in on the first decoded frame; [onShown] reports whether it is on screen so
 * the caller can hide the still artwork underneath only once motion actually plays.
 */
@Composable
fun MotionArtworkView(
    art: MotionArt,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onShown: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember { MotionLoopPlayer(context.applicationContext) }
    val hasFrame by player.hasFrame.collectAsState()
    val failed by player.failed.collectAsState()
    val decodedAspect by player.aspect.collectAsState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val latestShown by rememberUpdatedState(onShown)
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }

    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            visible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            latestShown(false)
            player.release()
        }
    }
    LaunchedEffect(art.url) { player.load(art.url, playing && visible) }
    // Motion keeps the song's rhythm: it holds still while the music is paused, like Apple Music.
    LaunchedEffect(playing, visible) { player.setPlaying(playing && visible) }
    val shown = hasFrame && !failed
    LaunchedEffect(shown) { latestShown(shown) }

    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(520), label = "motionAlpha")
    val aspect = decodedAspect.takeIf { it > 0f } ?: art.aspect
    Box(modifier.clipToBounds()) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { view -> player.setTextureView(view); view.isOpaque = false } },
            onRelease = { player.setTextureView(null) },
            modifier = Modifier
                .layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    if (w == Constraints.Infinity || h == Constraints.Infinity || w == 0 || h == 0) {
                        val p = measurable.measure(constraints)
                        return@layout layout(p.width, p.height) { p.place(0, 0) }
                    }
                    // Fill the box keeping the video's aspect; the overflow is clipped by the parent.
                    val (vw, vh) = if (w / h.toFloat() > aspect) w to (w / aspect).roundToInt() else (h * aspect).roundToInt() to h
                    val p = measurable.measure(Constraints.fixed(vw, vh))
                    layout(w, h) { p.place((w - vw) / 2, (h - vh) / 2) }
                }
                .graphicsLayer { this.alpha = alpha },
        )
    }
}
