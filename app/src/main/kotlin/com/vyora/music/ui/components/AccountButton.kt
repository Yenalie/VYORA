package com.vyora.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vyora.music.R
import com.vyora.music.data.account.AccountStore
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.liquidFill
import com.vyora.music.ui.theme.GlassFillStrong

/**
 * Apple Music's round account button in the large title: the YouTube profile photo while
 * signed in, a person glyph otherwise. Opens Settings.
 */
@Composable
fun AccountButton(onClick: () -> Unit, size: Dp = 44.dp) {
    val account by AccountStore.account.collectAsState()
    val photo = account?.photo
    if (photo == null) {
        GlassIconButton(Icons.Default.Person, stringResource(R.string.settings), onClick, size = size, iconSize = 22.dp)
        return
    }
    Box(
        Modifier
            .size(size)
            .glassClickable(pressedScale = 0.88f, onClick = onClick)
            .liquidFill(shape = CircleShape, fill = GlassFillStrong),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = photo,
            contentDescription = stringResource(R.string.settings),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size - 6.dp).clip(CircleShape),
        )
    }
}
