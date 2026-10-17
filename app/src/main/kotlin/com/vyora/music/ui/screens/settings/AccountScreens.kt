package com.vyora.music.ui.screens.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.vyora.music.R
import com.vyora.music.data.account.AccountStore
import com.vyora.music.data.account.InnerTubeAuth
import com.vyora.music.data.account.SyncEngine
import com.vyora.music.data.account.SyncError
import com.vyora.music.data.account.SyncStatus
import com.vyora.music.data.account.YouTubeAccount
import com.vyora.music.data.youtube.YouTubeConfig
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.theme.SystemColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraDestructive
import com.vyora.music.ui.theme.VyoraGroupedBackground
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlinx.coroutines.launch

private val DialogShape = RoundedCornerShape(26.dp)

@Composable
fun AccountSummaryRow(onClick: () -> Unit) {
    val account by AccountStore.account.collectAsState()
    val status by SyncEngine.status.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(account, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                account?.name ?: stringResource(R.string.account_sign_in),
                color = VyoraText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (account == null) stringResource(R.string.account_sign_in_desc) else statusText(status),
                color = if (status.error == SyncError.SESSION_EXPIRED) VyoraDestructive else VyoraTextMuted,
                style = MaterialTheme.typography.bodySmall, maxLines = 2,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = VyoraTextFaint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun AccountScreen(onBack: () -> Unit, onSignIn: () -> Unit) {
    val account by AccountStore.account.collectAsState()
    val status by SyncEngine.status.collectAsState()
    var cookieDialog by remember { mutableStateOf(false) }
    var signOutDialog by remember { mutableStateOf(false) }

    SettingsPage(title = stringResource(R.string.account_youtube), onBack = onBack) {
        val signedIn = account
        if (signedIn == null) {
            item(key = "intro") {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(null, 76.dp)
                    Spacer(Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.account_intro),
                        color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    )
                }
            }
            item(key = "sign_in") {
                SettingsGroup {
                    SettingsNavRow(
                        title = stringResource(R.string.account_sign_in_google),
                        icon = Icons.AutoMirrored.Filled.Login, iconTint = SystemColors.Red,
                        onClick = onSignIn,
                    )
                    SettingsNavRow(
                        title = stringResource(R.string.account_use_cookie),
                        icon = Icons.Default.Cookie, iconTint = SystemColors.Gray,
                        onClick = { cookieDialog = true },
                        divider = false,
                    )
                }
            }
        } else {
            item(key = "profile") {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(signedIn, 60.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(signedIn.name, color = VyoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            signedIn.handle?.let { Text(it, color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                        }
                    }
                }
            }
            item(key = "sync") {
                SettingsGroup(footer = stringResource(R.string.account_sync_footer)) {
                    SettingsRow(
                        title = stringResource(if (status.running) R.string.account_syncing else R.string.account_sync_now),
                        subtitle = statusText(status),
                        icon = Icons.Default.Sync, iconTint = SystemColors.Green,
                        onClick = if (status.running) null else ({ SyncEngine.syncNow() }),
                        divider = status.error == SyncError.SESSION_EXPIRED,
                        trailing = if (status.running) ({ CircularProgressIndicator(Modifier.size(20.dp), color = VyoraAccent, strokeWidth = 2.dp) }) else null,
                    )
                    if (status.error == SyncError.SESSION_EXPIRED) {
                        SettingsNavRow(
                            title = stringResource(R.string.account_sign_in_again),
                            icon = Icons.AutoMirrored.Filled.Login, iconTint = SystemColors.Red,
                            onClick = onSignIn, divider = false,
                        )
                    }
                }
            }
            item(key = "sign_out") {
                SettingsGroup {
                    SettingsActionRow(
                        title = stringResource(R.string.account_sign_out),
                        icon = Icons.AutoMirrored.Filled.Logout, iconTint = SystemColors.Red,
                        destructive = true, divider = false,
                        onClick = { signOutDialog = true },
                    )
                }
            }
        }
    }

    if (cookieDialog) CookieDialog(onDismiss = { cookieDialog = false }, onSignedIn = { cookieDialog = false })
    if (signOutDialog) {
        AlertDialog(
            onDismissRequest = { signOutDialog = false },
            containerColor = VyoraGroupedCell,
            titleContentColor = VyoraText,
            textContentColor = VyoraTextMuted,
            shape = DialogShape,
            title = { Text(stringResource(R.string.account_sign_out_title)) },
            text = { Text(stringResource(R.string.account_sign_out_desc)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { signOutDialog = false; signOut(keepLibrary = true) }) {
                        Text(stringResource(R.string.account_sign_out_keep), color = VyoraAccent)
                    }
                    TextButton(onClick = { signOutDialog = false; signOut(keepLibrary = false) }) {
                        Text(stringResource(R.string.account_sign_out_remove), color = VyoraDestructive)
                    }
                    TextButton(onClick = { signOutDialog = false }) {
                        Text(stringResource(R.string.cancel), color = VyoraText)
                    }
                }
            },
        )
    }
}

private fun signOut(keepLibrary: Boolean) {
    SyncEngine.signOut(keepLibrary)
    runCatching { CookieManager.getInstance().removeAllCookies(null) }
    runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
}

@Composable
private fun statusText(status: SyncStatus): String = when {
    status.running -> stringResource(R.string.account_syncing)
    status.error == SyncError.SESSION_EXPIRED -> stringResource(R.string.account_sync_expired)
    status.error == SyncError.OFFLINE -> stringResource(R.string.account_sync_offline)
    status.error == SyncError.FAILED -> stringResource(R.string.account_sync_failed)
    status.lastSyncAt <= 0L -> stringResource(R.string.account_never_synced)
    else -> stringResource(
        R.string.account_last_sync,
        DateUtils.getRelativeTimeSpanString(status.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
    )
}

@Composable
private fun Avatar(account: YouTubeAccount?, size: androidx.compose.ui.unit.Dp) {
    val photo = account?.photo
    Box(
        Modifier.size(size).clip(CircleShape).background(if (photo == null) SystemColors.Gray else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            AsyncImage(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
        }
    }
}

@Composable
private fun CookieDialog(onDismiss: () -> Unit, onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.account_cookie_invalid)
    val failed = stringResource(R.string.account_sign_in_failed)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        textContentColor = VyoraTextMuted,
        shape = DialogShape,
        title = { Text(stringResource(R.string.account_use_cookie)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.account_cookie_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    minLines = 3, maxLines = 5,
                    placeholder = { Text("SAPISID=…; SID=…", color = VyoraTextFaint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = VyoraDestructive) } },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = VyoraText, unfocusedTextColor = VyoraText,
                        cursorColor = VyoraAccent, focusedBorderColor = VyoraAccent, unfocusedBorderColor = VyoraTextFaint,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VyoraAccent)
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    val cookie = text.trim().removePrefix("Cookie:").trim()
                    if (!InnerTubeAuth.hasSession(cookie)) {
                        error = invalid
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        SyncEngine.signIn(cookie)
                            .onSuccess { Toast.makeText(context, it.name, Toast.LENGTH_SHORT).show(); onSignedIn() }
                            .onFailure { error = failed }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.done), color = if (text.isNotBlank() && !busy) VyoraAccent else VyoraTextFaint) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel), color = VyoraText) }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val failed = stringResource(R.string.account_sign_in_failed)

    com.vyora.music.ui.design.GlassPage(background = VyoraGroupedBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack, size = 44.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.account_login_title), color = VyoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VyoraAccent)
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loading = true }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (checking || url == null || !url.startsWith(YouTubeConfig.MUSIC_ORIGIN)) return
                                val cookie = CookieManager.getInstance().getCookie(YouTubeConfig.MUSIC_ORIGIN)
                                if (!InnerTubeAuth.hasSession(cookie)) return
                                checking = true
                                scope.launch {
                                    SyncEngine.signIn(cookie)
                                        .onSuccess {
                                            clearSignInWebData()
                                            onDone()
                                        }
                                        .onFailure {
                                            checking = false
                                            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                                        }
                                }
                            }
                        }
                        CookieManager.getInstance().removeAllCookies { loadUrl(YouTubeConfig.SIGN_IN_URL) }
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
        if (checking) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.clip(DialogShape).background(VyoraGroupedCell).padding(horizontal = 28.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = VyoraAccent)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.account_checking), color = VyoraText)
                }
            }
        }
    }
    // The session lives only in AccountStore (Keystore-encrypted). Never leave the raw Google cookies in
    // the WebView's own unencrypted cookie jar, whether sign-in succeeded or was abandoned.
    DisposableEffect(Unit) { onDispose { clearSignInWebData() } }
}

private fun clearSignInWebData() {
    runCatching {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
}
