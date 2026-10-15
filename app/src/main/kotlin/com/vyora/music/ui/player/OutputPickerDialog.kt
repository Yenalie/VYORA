package com.vyora.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouter
import com.vyora.music.R
import com.vyora.music.playback.cast.CastSupport
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted

private data class CastRoute(val id: String, val name: String, val description: String?, val connecting: Boolean, val selected: Boolean)

@Composable
fun OutputPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val status by CastSupport.status.collectAsState()
    val router = remember { runCatching { MediaRouter.getInstance(context) }.getOrNull() }
    val selector = remember { CastSupport.selector(context) }
    var routes by remember { mutableStateOf(emptyList<CastRoute>()) }
    var routeInfos by remember { mutableStateOf(emptyMap<String, MediaRouter.RouteInfo>()) }

    DisposableEffect(router, selector) {
        if (router == null || selector == null) return@DisposableEffect onDispose { }
        fun refresh() {
            val list = router.routes.filter { r ->
                !r.isDefault && !r.isBluetooth && r.isEnabled && r.matchesSelector(selector)
            }
            routeInfos = list.associateBy { it.id }
            routes = list.map { r ->
                CastRoute(
                    id = r.id,
                    name = r.name,
                    description = r.description,
                    connecting = r.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                    selected = r.isSelected,
                )
            }
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { router.removeCallback(callback) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        shape = RoundedCornerShape(26.dp),
        title = { Text(stringResource(R.string.output_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutputRow(
                    icon = Icons.Default.PhoneAndroid,
                    title = stringResource(R.string.output_this_device),
                    subtitle = null,
                    selected = !status.connected,
                    connecting = false,
                ) {
                    if (status.connected) CastSupport.stop(context)
                    onDismiss()
                }
                OutputRow(
                    icon = Icons.Default.Bluetooth,
                    title = stringResource(R.string.output_bluetooth),
                    subtitle = stringResource(R.string.output_bluetooth_hint),
                    selected = false,
                    connecting = false,
                ) {
                    onDismiss()
                    openOutputSwitcher(context)
                }
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    stringResource(R.string.output_cast_devices),
                    color = VyoraTextMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                if (routes.isEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = VyoraAccent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.output_searching), color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        stringResource(R.string.output_same_wifi),
                        color = VyoraTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                routes.forEach { route ->
                    OutputRow(
                        icon = if (route.selected) Icons.Default.CastConnected else Icons.Default.Cast,
                        title = route.name,
                        subtitle = if (route.connecting) stringResource(R.string.output_connecting) else route.description,
                        selected = route.selected,
                        connecting = route.connecting,
                    ) {
                        val info = routeInfos[route.id] ?: return@OutputRow
                        if (!route.selected) router?.selectRoute(info)
                    }
                }
                if (status.connected) CastVolume()
            }
        },
        confirmButton = {
            if (status.connected) {
                TextButton(onClick = { CastSupport.stop(context); onDismiss() }) {
                    Text(stringResource(R.string.output_stop_casting), color = VyoraAccent)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done), color = VyoraText) }
            }
        },
    )
}

@Composable
private fun CastVolume() {
    val context = LocalContext.current
    val session = remember { CastSupport.context(context)?.sessionManager?.currentCastSession }
    if (session == null) return
    var volume by remember { mutableFloatStateOf(runCatching { session.volume.toFloat() }.getOrDefault(0.5f)) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.volume), tint = VyoraTextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                runCatching { session.volume = it.toDouble() }
            },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = VyoraText, activeTrackColor = VyoraAccent),
        )
    }
}

@Composable
private fun OutputRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    selected: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, null, tint = if (selected) VyoraAccent else VyoraText, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = VyoraText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            connecting -> CircularProgressIndicator(Modifier.size(18.dp), color = VyoraAccent, strokeWidth = 2.dp)
            selected -> Icon(Icons.Default.Check, null, tint = VyoraAccent, modifier = Modifier.size(20.dp))
        }
    }
}
