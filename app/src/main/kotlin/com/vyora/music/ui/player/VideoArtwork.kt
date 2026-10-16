package com.vyora.music.ui.player

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vyora.music.playback.PlayerManager
import com.vyora.music.playback.video.VideoArtworkPlayer
import com.vyora.music.ui.design.VideoAmbience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val FOLLOW_INTERVAL_MS = 500L
private const val SAMPLE_INTERVAL_MS = 800L
private const val SAMPLE_WIDTH = 64
private const val SAMPLE_HEIGHT = 36

@Composable
fun VideoArtwork(videoId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val video = remember { VideoArtworkPlayer(context.applicationContext) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    val hasFrame by video.hasFrame.collectAsState()
    // Video only runs while the screen is visible: no decoding, network or bitmap sampling with the screen off
    // or the app in the background (the audio keeps playing from the service).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    fun visible() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    DisposableEffect(video) {
        onDispose {
            video.release()
            VideoAmbience.clear()
        }
    }

    LaunchedEffect(videoId) {
        VideoAmbience.clear()
        video.load(videoId, PlayerManager.currentPositionMs(), PlayerManager.isPlaying.value && visible())
        while (isActive) {
            video.follow(PlayerManager.currentPositionMs(), PlayerManager.isPlaying.value && visible())
            delay(if (visible()) FOLLOW_INTERVAL_MS else FOLLOW_INTERVAL_MS * 4)
        }
    }

    LaunchedEffect(videoId, hasFrame, texture) {
        val view = texture ?: return@LaunchedEffect
        if (!hasFrame) return@LaunchedEffect
        while (isActive) {
            if (!visible()) { delay(SAMPLE_INTERVAL_MS * 2); continue }
            val frame: Bitmap? = runCatching { view.getBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT) }.getOrNull()
            if (frame != null) withContext(Dispatchers.Default) { VideoAmbience.publish(frame) }
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    val alpha by animateFloatAsState(if (hasFrame) 1f else 0f, tween(450), label = "videoArtworkAlpha")
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                texture = view
                video.setTextureView(view)
            }
        },
        onRelease = {
            video.setTextureView(null)
            texture = null
        },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}
