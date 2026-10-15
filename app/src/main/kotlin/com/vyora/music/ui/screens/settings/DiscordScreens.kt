package com.vyora.music.ui.screens.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vyora.music.R
import com.vyora.music.data.discord.DiscordAccount
import com.vyora.music.data.discord.DiscordPresence
import com.vyora.music.data.discord.DiscordStatus
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.theme.SystemColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedBackground
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DiscordBlurple = Color(0xFF5865F2)

@Composable
fun DiscordScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsRepository.settings.collectAsState()
    val user by DiscordAccount.user.collectAsState()
    val status by DiscordPresence.status.collectAsState()
    var tokenDialog by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    SettingsPage(title = stringResource(R.string.discord_title), onBack = onBack) {
        item(key = "presence") {
            SettingsGroup(footer = stringResource(R.string.discord_footer)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.discord_show_presence),
                    icon = Icons.Default.Podcasts, iconTint = DiscordBlurple,
                    checked = settings.discordPresence && user != null,
                    onCheckedChange = { v ->
                        if (v && user == null) {
                            onLogin()
                        } else {
                            SettingsRepository.update { it.copy(discordPresence = v) }
                            if (v) DiscordPresence.refresh() else DiscordPresence.stop()
                        }
                    },
                    divider = false,
                )
            }
        }
        item(key = "account") {
            SettingsGroup(header = stringResource(R.string.discord_account)) {
                val current = user
                if (current == null) {
                    SettingsRow(
                        title = stringResource(R.string.discord_sign_in),
                        icon = Icons.Default.Login, iconTint = DiscordBlurple,
                        onClick = onLogin,
                    )
                    SettingsRow(
                        title = stringResource(R.string.discord_use_token),
                        icon = Icons.Default.Key, iconTint = SystemColors.Gray,
                        onClick = { tokenDialog = true },
                        divider = false,
                    )
                } else {
                    SettingsRow(
                        title = current.name,
                        subtitle = statusLabel(status, settings.discordPresence),
                        icon = Icons.Default.Person, iconTint = DiscordBlurple,
                        onClick = {},
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.discord_sign_out),
                        destructive = true,
                        icon = Icons.Default.Logout, iconTint = SystemColors.Red,
                        onClick = { confirmSignOut = true },
                        divider = false,
                    )
                }
            }
        }
        item(key = "warning") {
            Text(
                stringResource(R.string.discord_warning),
                color = VyoraTextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }

    if (tokenDialog) {
        TokenDialog(
            onDismiss = { tokenDialog = false },
            onSaved = {
                tokenDialog = false
                SettingsRepository.update { it.copy(discordPresence = true) }
                DiscordPresence.refresh()
            },
        )
    }
    if (confirmSignOut) {
        ConfirmDialog(
            message = stringResource(R.string.discord_sign_out_confirm),
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                DiscordPresence.stop()
                DiscordAccount.signOut()
                SettingsRepository.update { it.copy(discordPresence = false) }
                scope.launch { Toast.makeText(context, R.string.discord_signed_out, Toast.LENGTH_SHORT).show() }
            },
        )
    }
}

@Composable
private fun statusLabel(status: DiscordStatus, enabled: Boolean): String = stringResource(
    when {
        !enabled -> R.string.discord_status_off
        status == DiscordStatus.CONNECTED -> R.string.discord_status_connected
        status == DiscordStatus.CONNECTING -> R.string.discord_status_connecting
        status == DiscordStatus.AUTH_FAILED -> R.string.discord_status_auth_failed
        status == DiscordStatus.ERROR -> R.string.discord_status_error
        else -> R.string.discord_status_idle
    }
)

@Composable
private fun TokenDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        textContentColor = VyoraTextMuted,
        shape = RoundedCornerShape(26.dp),
        title = { Text(stringResource(R.string.discord_use_token)) },
        text = {
            Column {
                Text(stringResource(R.string.discord_token_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; error = false },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.discord_token_label)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = VyoraText,
                        unfocusedTextColor = VyoraText,
                        focusedBorderColor = VyoraAccent,
                        cursorColor = VyoraAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) {
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.discord_token_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && token.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        DiscordAccount.signIn(token)
                            .onSuccess { onSaved() }
                            .onFailure { error = true }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = VyoraAccent, strokeWidth = 2.dp)
                else Text(stringResource(R.string.save), color = VyoraAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel), color = VyoraText) }
        },
    )
}

private const val TOKEN_SCRIPT = """
(function () {
  function send(t) { if (t) { VyoraDiscord.onToken(String(t).replace(/^"|"$/g, '')); return true; } return false; }
  try {
    var frame = document.createElement('iframe');
    frame.style.display = 'none';
    document.body.appendChild(frame);
    var stored = frame.contentWindow.localStorage.getItem('token');
    frame.remove();
    if (send(stored)) return;
  } catch (e) {}
  try {
    var modules = [];
    window.webpackChunkdiscord_app.push([[Symbol()], {}, function (r) { for (var k in r.c) modules.push(r.c[k]); }]);
    window.webpackChunkdiscord_app.pop();
    for (var i = 0; i < modules.length; i++) {
      var m = modules[i];
      var e = m && m.exports;
      if (!e) continue;
      var c = [e.default, e.Z, e.ZP, e];
      for (var j = 0; j < c.length; j++) {
        if (c[j] && typeof c[j].getToken === 'function') { if (send(c[j].getToken())) return; }
      }
    }
  } catch (e) {}
})();
"""

private fun clearDiscordWebData() {
    val cookies = CookieManager.getInstance()
    listOf("https://discord.com", "https://www.discord.com").forEach { origin ->
        cookies.getCookie(origin)?.split(';')?.forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
        }
        WebStorage.getInstance().deleteOrigin(origin)
    }
    cookies.flush()
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun DiscordLoginScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val failed = stringResource(R.string.discord_sign_in_failed)
    val success = stringResource(R.string.discord_signed_in)

    fun accept(token: String) {
        if (checking || token.isBlank()) return
        checking = true
        scope.launch {
            DiscordAccount.signIn(token)
                .onSuccess {
                    SettingsRepository.update { it.copy(discordPresence = true) }
                    DiscordPresence.refresh()
                    clearDiscordWebData()
                    Toast.makeText(context, success, Toast.LENGTH_SHORT).show()
                    onDone()
                }
                .onFailure {
                    checking = false
                    Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                }
        }
    }

    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        while (true) {
            delay(1_500)
            if (!checking && view.url?.contains("discord.com") == true && view.url?.contains("/login") != true) {
                view.evaluateJavascript(TOKEN_SCRIPT, null)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(VyoraGroupedBackground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onDone, size = 44.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.discord_sign_in), color = VyoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DiscordBlurple)
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onToken(token: String) {
                                post { accept(token) }
                            }
                        }, "VyoraDiscord")
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                        }
                        clearDiscordWebData()
                        loadUrl("https://discord.com/login")
                        webView = this
                    }
                },
                onRelease = {
                    it.removeJavascriptInterface("VyoraDiscord")
                    it.stopLoading()
                    it.destroy()
                },
            )
        }
        if (checking) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(VyoraGroupedCell).padding(horizontal = 28.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = DiscordBlurple)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.discord_checking), color = VyoraText)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { webView = null } }
}
