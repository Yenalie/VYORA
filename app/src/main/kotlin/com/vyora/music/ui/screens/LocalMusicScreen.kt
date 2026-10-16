package com.vyora.music.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vyora.music.R
import com.vyora.music.data.local.AudioTier
import com.vyora.music.data.local.LocalLibrary
import com.vyora.music.data.local.LocalTrack
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.components.QualityBadge
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.design.GlassChip
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlinx.coroutines.launch

enum class LocalFilter { ALL, LOSSLESS, HI_RES }

internal fun List<LocalTrack>.filteredBy(filter: LocalFilter): List<LocalTrack> = when (filter) {
    LocalFilter.ALL -> this
    LocalFilter.LOSSLESS -> filter { it.tier != AudioTier.LOSSY }
    LocalFilter.HI_RES -> filter { it.tier == AudioTier.HI_RES_LOSSLESS }
}

@Composable
fun LocalMusicScreen(
    onBack: () -> Unit,
    onPlay: (List<SimpleItem>, Int, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by LocalLibrary.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(LocalFilter.LOSSLESS) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var denials by rememberSaveable { mutableIntStateOf(0) }
    val bottomInset = LocalBottomBarInset.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) denials++
        scope.launch { LocalLibrary.refresh(context) }
    }
    LifecycleResumeEffect(Unit) {
        scope.launch { LocalLibrary.refresh(context) }
        onPauseOrDispose { }
    }
    LaunchedEffect(state) {
        if (state is LocalLibrary.State.NoPermission && !asked) {
            asked = true
            launcher.launch(LocalLibrary.permission)
        }
    }

    val all = (state as? LocalLibrary.State.Ready)?.tracks.orEmpty()
    val losslessCount = remember(all) { all.count { it.tier != AudioTier.LOSSY } }
    val hiResCount = remember(all) { all.count { it.tier == AudioTier.HI_RES_LOSSLESS } }
    // Nothing lossless on the device yet: show everything rather than an empty "Lossless" list.
    val effective = if (filter != LocalFilter.ALL && losslessCount == 0 && all.isNotEmpty()) LocalFilter.ALL else filter
    val shown = remember(all, effective) { all.filteredBy(effective) }
    val songs = remember(shown) { shown.map { it.item } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 28.dp + bottomInset),
    ) {
        item(key = "top") {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack, size = 44.dp, iconSize = 20.dp)
                    Spacer(Modifier.weight(1f))
                    if (state is LocalLibrary.State.Ready) {
                        GlassIconButton(Icons.Default.Refresh, stringResource(R.string.local_rescan), { scope.launch { LocalLibrary.refresh(context) } }, size = 44.dp, iconSize = 20.dp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                LargeTitleHeader(
                    title = stringResource(R.string.local_title),
                    subtitle = if (all.isNotEmpty()) {
                        pluralStringResource(R.plurals.track_count, all.size, all.size) + " · " +
                            stringResource(R.string.local_lossless_count, losslessCount)
                    } else stringResource(R.string.local_subtitle),
                )
                Spacer(Modifier.height(14.dp))
            }
        }

        when (val s = state) {
            LocalLibrary.State.NoPermission -> item(key = "permission") {
                val permanentlyDenied = denials >= 2
                GlassEmptyState(
                    icon = Icons.Default.GraphicEq,
                    title = stringResource(R.string.local_permission_title),
                    body = stringResource(R.string.local_permission_body),
                    actionLabel = stringResource(if (permanentlyDenied) R.string.local_open_settings else R.string.local_permission_allow),
                    onAction = {
                        if (permanentlyDenied) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } else {
                            launcher.launch(LocalLibrary.permission)
                        }
                    },
                )
            }
            LocalLibrary.State.Idle, LocalLibrary.State.Scanning -> item(key = "scanning") {
                Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = VyoraAccent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.local_scanning), color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            is LocalLibrary.State.Ready -> {
                if (s.tracks.isEmpty()) {
                    item(key = "empty") {
                        GlassEmptyState(
                            icon = Icons.Default.MusicOff,
                            title = stringResource(R.string.local_empty_title),
                            body = stringResource(R.string.local_empty_body),
                        )
                    }
                } else {
                    item(key = "filters") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { GlassChip("${stringResource(R.string.local_filter_all)} ${all.size}", effective == LocalFilter.ALL, { filter = LocalFilter.ALL }) }
                            item { GlassChip("${stringResource(R.string.quality_lossless)} $losslessCount", effective == LocalFilter.LOSSLESS, { filter = LocalFilter.LOSSLESS }, icon = Icons.Default.GraphicEq) }
                            if (hiResCount > 0) item { GlassChip("${stringResource(R.string.quality_hires)} $hiResCount", effective == LocalFilter.HI_RES, { filter = LocalFilter.HI_RES }) }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassButton(stringResource(R.string.play_all), { onPlay(songs, 0, false) }, Modifier.weight(1f), icon = Icons.Default.PlayArrow, enabled = songs.isNotEmpty())
                            GlassButton(stringResource(R.string.shuffle), { onPlay(songs, songs.indices.random(), true) }, Modifier.weight(1f), icon = Icons.Default.Shuffle, enabled = songs.isNotEmpty())
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    itemsIndexed(shown, key = { _, track -> track.item.id }) { i, track ->
                        ItemRow(
                            item = track.item,
                            onClick = { onPlay(songs, i, false) },
                            modifier = Modifier.animateItem(),
                            showSeparator = i < shown.lastIndex,
                            badge = if (track.tier != AudioTier.LOSSY) ({ QualityBadge(track.tier) }) else null,
                        )
                    }
                }
            }
        }
    }
}
