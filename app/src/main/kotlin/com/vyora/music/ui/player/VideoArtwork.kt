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

/** How often the picture is re-aligned with the song, and its colour read. */
private const val FOLLOW_INTERVAL_MS = 500L
private const val SAMPLE_INTERVAL_MS = 800L
private const val SAMPLE_WIDTH = 64
private const val SAMPLE_HEIGHT = 36

/**
 * The music video of [videoId], muted and kept in sync with the song, fading in over the
 * cover once its first frame is ready. While it shows, its colour drives [VideoAmbience].
 * Drawn through a TextureView so it clips to the artwork's rounded corners.
 */
@Composable
fun VideoArtwork(videoId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val video = remember { VideoArtworkPlayer(context.applicationContext) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    val hasFrame by video.hasFrame.collectAsState()

    DisposableEffect(video) {
        onDispose {
            video.release()
            VideoAmbience.clear()
        }
    }

    LaunchedEffect(videoId) {
        VideoAmbience.clear()
        video.load(videoId, PlayerManager.currentPositionMs(), PlayerManager.isPlaying.value)
        while (isActive) {
            video.follow(PlayerManager.currentPositionMs(), PlayerManager.isPlaying.value)
            delay(FOLLOW_INTERVAL_MS)
        }
    }

    LaunchedEffect(videoId, hasFrame, texture) {
        val view = texture ?: return@LaunchedEffect
        if (!hasFrame) return@LaunchedEffect
        while (isActive) {
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
