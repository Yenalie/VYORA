package com.vyora.music.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vyora.music.ui.theme.GlassFillStrong
import com.vyora.music.ui.theme.VyoraTextFaint

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.Small,
    placeholder: ImageVector = Icons.Default.MusicNote,
    placeholderSize: Dp = 22.dp,
    contentDescription: String? = null,
    placeholderKey: String? = null,
) {
    Box(
        modifier.clip(shape).glass(shape = shape, fill = GlassFillStrong, sheen = false),
        contentAlignment = Alignment.Center,
    ) {
        var loaded by remember(url) { mutableStateOf(false) }
        if (!loaded) Icon(placeholder, null, tint = VyoraTextFaint, modifier = Modifier.size(placeholderSize))
        val context = LocalContext.current
        val model = remember(url, placeholderKey) {
            if (placeholderKey == null || placeholderKey == url) url
            else ImageRequest.Builder(context).data(url).placeholderMemoryCacheKey(placeholderKey).crossfade(180).build()
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            onError = { loaded = false },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
