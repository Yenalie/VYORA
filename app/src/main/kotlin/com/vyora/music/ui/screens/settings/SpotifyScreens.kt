package com.vyora.music.ui.screens.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.vyora.music.R
import com.vyora.music.data.importer.SpotifyImport
import com.vyora.music.data.spotify.SpotifyAccount
import com.vyora.music.data.spotify.SpotifyPlaylistInfo
import com.vyora.music.data.spotify.SpotifyTrackInfo
import com.vyora.music.playback.PlayerManager
import com.vyora.music.ui.components.VyoraTextField
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.theme.SystemColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedBackground
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraSeparator
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SpotifyGreen = Color(0xFF1DB954)

/** Spotify account: sign in with the web login, then browse Liked Songs and playlists. */
@Composable
fun SpotifyAccountScreen(onBack: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by SpotifyAccount.profile.collectAsState()
    var showLogin by remember { mutableStateOf(false) }
    var showCookie by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylistInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.spotify_sign_in_failed)
    val signedIn = stringResource(R.string.spotify_signed_in)

    fun accept(spDc: String, spKey: String) {
        if (busy) return
        busy = true
        showLogin = false
        showCookie = false
        scope.launch {
            SpotifyAccount.signIn(spDc, spKey)
                .onSuccess { Toast.makeText(context, signedIn, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, failed + "\n" + (it.message ?: ""), Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    LaunchedEffect(profile?.id) {
        playlists = null
        loadError = null
        if (profile == null) return@LaunchedEffect
        runCatching {
            val out = mutableListOf<SpotifyPlaylistInfo>()
            do {
                val page = SpotifyAccount.playlists(out.size, 50)
                out += page.items
            } while (page.items.isNotEmpty() && out.size < page.total && out.size < 500)
            out
        }.onSuccess { playlists = it }.onFailure { loadError = it.message ?: "Error" }
    }

    SettingsPage(title = stringResource(R.string.spotify_title), onBack = onBack) {
        item(key = "card") {
            val me = profile
            Column(
                Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(SpotifyGreen.copy(alpha = 0.35f), VyoraGroupedCell)))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(SpotifyGreen), contentAlignment = Alignment.Center) {
                    if (me?.avatarUrl != null) {
                        AsyncImage(me.avatarUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(76.dp).clip(CircleShape))
                    } else {
                        Icon(Icons.Default.MusicNote, null, tint = Color.Black, modifier = Modifier.size(36.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (me != null) {
                    Text(me.name, color = VyoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.spotify_connected), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.spotify_connect_title), color = VyoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.spotify_connect_body), color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    if (busy) {
                        CircularProgressIndicator(color = SpotifyGreen)
                    } else {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(SpotifyGreen)
                                .clickable { showLogin = true }.padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Login, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.spotify_sign_in), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        if (profile == null) {
            item(key = "cookie") {
                SettingsGroup(footer = stringResource(R.string.spotify_footer)) {
                    SettingsRow(
                        title = stringResource(R.string.spotify_use_cookie),
                        icon = Icons.Default.Key, iconTint = SystemColors.Gray,
                        onClick = { showCookie = true }, divider = false,
                    )
                }
            }
        } else {
            item(key = "liked") {
                SettingsGroup(header = stringResource(R.string.spotify_library)) {
                    SettingsNavRow(
                        title = stringResource(R.string.spotify_liked),
                        icon = Icons.Default.Favorite, iconTint = Color(0xFF6E44FF),
                        onClick = { onOpenPlaylist(SpotifyAccount.LIKED_URI) },
                        divider = false,
                    )
                }
            }
            item(key = "playlists") {
                SettingsGroup(header = stringResource(R.string.spotify_playlists)) {
                    val list = playlists
                    when {
                        loadError != null -> Text(
                            stringResource(R.string.spotify_load_failed) + "\n" + loadError,
                            color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                        list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = SpotifyGreen, modifier = Modifier.size(28.dp))
                        }
                        list.isEmpty() -> Text(
                            stringResource(R.string.spotify_no_playlists), color = VyoraTextMuted,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                        )
                        else -> list.forEachIndexed { i, p ->
                            ArtworkRow(
                                image = p.imageUrl,
                                title = p.name,
                                subtitle = listOfNotNull(
                                    p.owner,
                                    p.total?.let { pluralStringResource(R.plurals.track_count, it, it) },
                                ).joinToString(" · "),
                                divider = i < list.lastIndex,
                                onClick = { onOpenPlaylist(p.uri) },
                            )
                        }
                    }
                }
            }
            item(key = "out") {
                SettingsGroup(footer = stringResource(R.string.spotify_footer)) {
                    SettingsActionRow(
                        title = stringResource(R.string.spotify_sign_out), destructive = true,
                        icon = Icons.AutoMirrored.Filled.Logout, iconTint = SystemColors.Red,
                        onClick = { confirmSignOut = true }, divider = false,
                    )
                }
            }
        }
    }

    if (showLogin) SpotifyLoginDialog(onDismiss = { showLogin = false }, onCookies = ::accept)
    if (showCookie) CookieDialog(onDismiss = { showCookie = false }, onSave = { accept(it, "") })
    if (confirmSignOut) {
        ConfirmDialog(
            message = stringResource(R.string.spotify_sign_out_confirm),
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                SpotifyAccount.signOut()
                clearSpotifyWebData()
            },
        )
    }
}

@Composable
private fun ArtworkRow(image: String?, title: String, subtitle: String, divider: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(VyoraSeparator),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = VyoraText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing?.invoke()
        }
        if (divider) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 76.dp).fillMaxWidth().height(0.5.dp).background(VyoraSeparator))
        }
    }
}

/** Matches Spotify tracks to YouTube Music in small batches so playback starts right away. */
private object SpotifyPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun play(tracks: List<SpotifyTrackInfo>, start: Int, shuffle: Boolean, onNothing: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            val order = if (shuffle) tracks.shuffled() else tracks.drop(start) + tracks.take(start)
            var started = false
            order.chunked(8).forEach { chunk ->
                val songs = withContext(Dispatchers.IO) { SpotifyImport.matchAll(chunk.map(SpotifyTrackInfo::toImport)) }.filterNotNull()
                if (songs.isEmpty()) return@forEach
                if (!started) {
                    PlayerManager.setQueue(songs, 0, false)
                    started = true
                } else {
                    songs.forEach(PlayerManager::addToQueue)
                }
            }
            if (!started) onNothing()
        }
    }
}

/** One Spotify playlist (or Liked Songs): play it through YouTube Music or save it as a VYORA playlist. */
@Composable
fun SpotifyListScreen(uri: String, onBack: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liked = uri == SpotifyAccount.LIKED_URI
    val likedName = stringResource(R.string.spotify_liked)
    var name by remember { mutableStateOf(if (liked) likedName else "") }
    var tracks by remember { mutableStateOf<List<SpotifyTrackInfo>?>(null) }
    var loaded by remember { mutableStateOf(0 to 0) }
    var error by remember { mutableStateOf<String?>(null) }
    val progress by SpotifyImport.progress.collectAsState()
    val noMatch = stringResource(R.string.spotify_no_match)
    val starting = stringResource(R.string.spotify_starting)

    LaunchedEffect(uri) {
        SpotifyImport.reset()
        runCatching { SpotifyAccount.allTracks(uri) { done, total -> loaded = done to total } }
            .onSuccess { (n, list) -> if (!liked && n.isNotBlank()) name = n; tracks = list }
            .onFailure { error = it.message ?: "Error" }
    }

    fun play(start: Int, shuffle: Boolean) {
        val list = tracks ?: return
        Toast.makeText(context, starting, Toast.LENGTH_SHORT).show()
        SpotifyPlayer.play(list, start, shuffle) { Toast.makeText(context, noMatch, Toast.LENGTH_SHORT).show() }
    }

    SettingsPage(title = name.ifBlank { stringResource(R.string.spotify_title) }, onBack = { SpotifyImport.reset(); onBack() }) {
        val list = tracks
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val cover = list?.firstOrNull()?.imageUrl
                Box(
                    Modifier.size(180.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (liked) Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFFC4EFD9))) else Brush.linearGradient(listOf(VyoraSeparator, VyoraSeparator))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (liked) Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(72.dp))
                    else if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(12.dp))
                Text(name, color = VyoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    if (list != null) pluralStringResource(R.plurals.track_count, list.size, list.size) else "Spotify",
                    color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(14.dp))
                if (list != null && list.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassButton(stringResource(R.string.play), { play(0, false) }, Modifier.weight(1f), icon = Icons.Default.PlayArrow)
                        GlassButton(stringResource(R.string.shuffle), { play(0, true) }, Modifier.weight(1f), icon = Icons.Default.Shuffle)
                    }
                    Spacer(Modifier.height(10.dp))
                    when (val p = progress) {
                        is SpotifyImport.Progress.Matching -> {
                            Text(stringResource(R.string.import_matching, p.done, p.total), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done / p.total.toFloat() }, modifier = Modifier.fillMaxWidth(), color = VyoraAccent)
                        }
                        is SpotifyImport.Progress.Done -> GlassButton(
                            stringResource(R.string.import_open), { SpotifyImport.reset(); onOpenPlaylist(p.playlistId) },
                            Modifier.fillMaxWidth(), icon = Icons.Default.LibraryMusic,
                        )
                        else -> GlassButton(
                            stringResource(R.string.spotify_save_playlist),
                            { scope.launch { SpotifyImport.importListing(SpotifyImport.Listing(name.ifBlank { "Spotify" }, list.map(SpotifyTrackInfo::toImport))) } },
                            Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        )
                    }
                }
            }
        }
        item(key = "tracks") {
            SettingsGroup {
                when {
                    error != null -> Text(stringResource(R.string.spotify_load_failed) + "\n" + error, color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    list == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SpotifyGreen, modifier = Modifier.size(28.dp))
                        if (loaded.second > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("${loaded.first} / ${loaded.second}", color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    list.isEmpty() -> Text(stringResource(R.string.import_empty), color = VyoraTextMuted, modifier = Modifier.padding(16.dp))
                    else -> list.forEachIndexed { i, t ->
                        ArtworkRow(t.imageUrl, t.title, t.artist, divider = i < list.lastIndex, onClick = { play(i, false) })
                    }
                }
            }
        }
    }
}

private fun cleanUserAgent(ua: String): String =
    ua.replace("; wv", "").replace(Regex("""Version/\d+(\.\d+)*\s"""), "")

/** Removes only Spotify's cookies and storage; the YouTube sign-in shares the same CookieManager. */
internal fun clearSpotifyWebData() {
    val cookies = CookieManager.getInstance()
    listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://www.spotify.com", "https://spotify.com").forEach { origin ->
        cookies.getCookie(origin)?.split(';')?.forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) {
                cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
                cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.spotify.com")
            }
        }
        WebStorage.getInstance().deleteOrigin(origin)
    }
    cookies.flush()
}

private fun readSpotifyCookies(manager: CookieManager): Map<String, String> {
    val out = linkedMapOf<String, String>()
    manager.flush()
    listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://spotify.com").forEach { url ->
        manager.getCookie(url)?.split(';')?.map(String::trim)?.forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) out[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
    }
    return out
}

internal fun isSpotifyLoginHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "spotify.com" || h.endsWith(".spotify.com") || h.endsWith(".scdn.co") ||
        h == "accounts.google.com" || h.endsWith(".facebook.com") || h == "appleid.apple.com"
}

/** Spotify's own login page in a WebView (Yuma's SpotifyLoginSheet); the sp_dc cookie is all VYORA keeps. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyLoginDialog(onDismiss: () -> Unit, onCookies: (String, String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf(false) }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(VyoraGroupedBackground)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.Rounded.Close, stringResource(R.string.cancel), onDismiss, size = 44.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.spotify_sign_in), color = VyoraText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.spotify_waiting_login), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = SpotifyGreen)
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
                factory = { ctx ->
                    WebView(ctx).apply {
                        val manager = CookieManager.getInstance()
                        manager.setAcceptCookie(true)
                        manager.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = cleanUserAgent(settings.userAgentString)
                        fun capture(): Boolean {
                            if (captured) return true
                            val c = readSpotifyCookies(manager)
                            val dc = c["sp_dc"].orEmpty()
                            if (dc.isBlank()) return false
                            captured = true
                            onCookies(dc, c["sp_key"].orEmpty())
                            return true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loading = true; capture() }
                            override fun onPageFinished(view: WebView?, url: String?) { loading = false; capture() }
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (capture()) return true
                                val scheme = request?.url?.scheme
                                if (scheme != "https" && scheme != "http") return true
                                return !isSpotifyLoginHost(request.url?.host)
                            }
                        }
                        clearSpotifyWebData()
                        loadUrl(SpotifyAccount.LOGIN_URL)
                        webView = this
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
    }
    DisposableEffect(Unit) { onDispose { webView = null } }
}

@Composable
private fun CookieDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        textContentColor = VyoraTextMuted,
        shape = RoundedCornerShape(26.dp),
        title = { Text(stringResource(R.string.spotify_use_cookie)) },
        text = {
            Column {
                Text(stringResource(R.string.spotify_cookie_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                VyoraTextField(value, { value = it }, "sp_dc", password = true)
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onSave(value) }) { Text(stringResource(R.string.save), color = VyoraAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = VyoraText) } },
    )
}
