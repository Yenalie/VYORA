package com.vyora.music.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Message
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.net.toUri
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
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

    if (showLogin) {
        SpotifyLoginPage(onDismiss = { showLogin = false }, onCookies = ::accept)
        return
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
            item(key = "footer") {
                Text(
                    stringResource(R.string.spotify_footer), color = VyoraTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
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

/** Yuma's getCleanChromeUserAgent: Google and Facebook refuse sign-in from a user agent that says "wv". */
internal fun cleanUserAgent(ua: String): String =
    ua.replace("; wv", "").replace(Regex("""Version/\d+\.\d+\s?"""), "")

/** Removes only Spotify's cookies and storage (used on sign-out). */
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

/** Yuma's resetAuthWebViewSession: start every login from an empty browser so a stale session can't win. */
private fun resetLoginSession(webView: WebView, onReady: () -> Unit) {
    webView.stopLoading()
    webView.clearHistory()
    webView.clearFormData()
    webView.clearCache(true)
    WebStorage.getInstance().deleteAllData()
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(webView, true)
    cookies.removeSessionCookies {
        cookies.removeAllCookies {
            cookies.flush()
            onReady()
        }
    }
}

/** Reads cookies from every Spotify origin plus the page currently shown (Yuma's readSpotifyCookies). */
private fun readSpotifyCookies(manager: CookieManager, currentUrl: String?): Map<String, String> {
    val urls = linkedSetOf("https://open.spotify.com", "https://accounts.spotify.com", "https://spotify.com")
    currentUrl?.let(::spotifyCookieOrigin)?.let(urls::add)
    val out = linkedMapOf<String, String>()
    manager.flush()
    urls.forEach { url ->
        manager.getCookie(url)?.split(';')?.map(String::trim)?.forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) out[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
    }
    return out
}

internal fun spotifyCookieOrigin(url: String): String? {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (host != "spotify.com" && !host.endsWith(".spotify.com")) return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: "https"
    return "$scheme://$host"
}

internal fun isWebViewLoadableUrl(url: String): Boolean =
    runCatching { url.toUri().scheme?.lowercase() }.getOrNull() in setOf("http", "https", "javascript", "data", "blob")

/** `intent://` links (for example "open in the Spotify app") carry a web fallback; load that instead. */
private fun intentFallbackUrl(url: String): String? =
    runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        ?.getStringExtra("browser_fallback_url")
        ?.takeIf(::isWebViewLoadableUrl)

private class LoginWebState {
    var captured = false
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureSpotifyLogin() {
    val ua = cleanUserAgent(WebSettings.getDefaultUserAgent(context))
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = ua
    }
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(this, true)
}

private fun WebView.destroyLoginView() {
    stopLoading()
    loadUrl("about:blank")
    (parent as? ViewGroup)?.removeView(this)
    destroy()
}

/** Shared page client: try to grab sp_dc on every navigation, keep http(s) inside, unwrap intent:// links. */
private fun loginClient(capture: (String?) -> Boolean, onLoading: (Boolean) -> Unit) = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString()
        if (capture(url)) return true
        if (url.isNullOrBlank() || isWebViewLoadableUrl(url)) return false
        intentFallbackUrl(url)?.let(view::loadUrl)
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoading(true)
        capture(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onLoading(false)
        capture(url)
    }
}

/** Google / Facebook / Apple sign-in on Spotify opens popup windows; host them on top of the login page. */
private class LoginChromeClient(
    private val container: FrameLayout,
    private val root: WebView,
    private val capture: (String?) -> Boolean,
    private val onLoading: (Boolean) -> Unit,
    private val onActive: (WebView) -> Unit,
) : WebChromeClient() {
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        closePopups()
        val popup = WebView(view.context).apply {
            configureSpotifyLogin()
            webViewClient = loginClient(capture, onLoading)
            webChromeClient = LoginChromeClient(container, root, capture, onLoading, onActive)
        }
        container.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popup.bringToFront()
        popup.requestFocus()
        onActive(popup)
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView) {
        window.destroyLoginView()
        onActive(root)
    }

    private fun closePopups() {
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i) as? WebView ?: continue
            if (child !== root) child.destroyLoginView()
        }
        onActive(root)
    }
}

/**
 * Spotify's own login page, ported from YumaPlayer's SpotifyLoginSheet: a clean Chrome user agent, popups for
 * third-party sign-in, sp_dc captured from any Spotify origin, and an sp_dc field for logging in by token.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyLoginPage(onDismiss: () -> Unit, onCookies: (String, String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var active by remember { mutableStateOf<WebView?>(null) }
    var root by remember { mutableStateOf<WebView?>(null) }
    var token by rememberSaveable { mutableStateOf("") }
    val state = remember { LoginWebState() }
    val currentOnCookies by rememberUpdatedState(onCookies)

    BackHandler {
        val view = active
        val main = root
        when {
            view?.canGoBack() == true -> view.goBack()
            view != null && main != null && view !== main -> { view.destroyLoginView(); active = main }
            else -> onDismiss()
        }
    }

    Column(Modifier.fillMaxSize().background(VyoraGroupedBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Rounded.Close, stringResource(R.string.cancel), onDismiss, size = 44.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.spotify_sign_in), color = VyoraText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.spotify_waiting_login), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            VyoraTextField(token, { token = it }, "sp_dc", modifier = Modifier.weight(1f), password = true)
            Spacer(Modifier.width(10.dp))
            GlassButton(
                text = stringResource(R.string.spotify_login_token),
                onClick = { token.trim().takeIf(String::isNotBlank)?.let { currentOnCookies(it, "") } },
                enabled = token.isNotBlank(),
                content = Color.Black,
                fill = SpotifyGreen,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxSize(), color = SpotifyGreen)
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
            factory = { ctx ->
                val container = FrameLayout(ctx)
                val manager = CookieManager.getInstance()
                val capture: (String?) -> Boolean = capture@{ url ->
                    if (state.captured) return@capture true
                    val c = readSpotifyCookies(manager, url)
                    val dc = c["sp_dc"].orEmpty()
                    if (dc.isBlank()) return@capture false
                    state.captured = true
                    manager.flush()
                    currentOnCookies(dc, c["sp_key"].orEmpty())
                    true
                }
                val onLoading: (Boolean) -> Unit = { loading = it }
                val main = WebView(ctx).apply {
                    configureSpotifyLogin()
                    webViewClient = loginClient(capture, onLoading)
                }
                main.webChromeClient = LoginChromeClient(container, main, capture, onLoading) { active = it }
                container.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                root = main
                active = main
                resetLoginSession(main) { main.loadUrl(SpotifyAccount.LOGIN_URL) }
                container
            },
            onRelease = { container ->
                for (i in container.childCount - 1 downTo 0) (container.getChildAt(i) as? WebView)?.destroyLoginView()
            },
        )
    }
    DisposableEffect(Unit) { onDispose { active = null; root = null } }
}
