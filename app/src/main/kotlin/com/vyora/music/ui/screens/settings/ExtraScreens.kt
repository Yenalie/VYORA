package com.vyora.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyora.music.R
import com.vyora.music.data.backup.Backup
import com.vyora.music.data.importer.SpotifyImport
import com.vyora.music.data.scrobble.Scrobbler
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.ui.components.VyoraTextField
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.theme.SystemColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun openUrl(context: android.content.Context, url: String) = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Last.fm and ListenBrainz scrobbling. */
@Composable
fun ScrobbleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsRepository.settings.collectAsState()
    val accounts by Scrobbler.accounts.collectAsState()
    val pending by Scrobbler.pending.collectAsState()

    var apiKey by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var lbToken by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lfmError by remember { mutableStateOf<String?>(null) }
    var lbError by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.scrobble_login_failed)

    SettingsPage(title = stringResource(R.string.scrobble_title), onBack = onBack) {
        item(key = "intro") {
            Text(
                stringResource(R.string.scrobble_intro), color = VyoraTextMuted,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "lastfm") {
            val signedIn = accounts.lastFmUser
            SettingsGroup(header = "Last.fm", footer = if (signedIn == null) stringResource(R.string.scrobble_lastfm_footer) else null) {
                if (signedIn != null) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.scrobble_enable),
                        subtitle = stringResource(R.string.scrobble_signed_in_as, signedIn),
                        icon = Icons.Default.GraphicEq, iconTint = Color(0xFFD51007),
                        checked = settings.scrobbleLastFm,
                        onCheckedChange = { v -> SettingsRepository.update { it.copy(scrobbleLastFm = v) } },
                    )
                    SettingsActionRow(stringResource(R.string.scrobble_sign_out), { Scrobbler.logoutLastFm() }, destructive = true, divider = false)
                } else {
                    SettingsControlRow(divider = false) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            VyoraTextField(apiKey, { apiKey = it.trim(); lfmError = null }, stringResource(R.string.scrobble_api_key))
                            VyoraTextField(secret, { secret = it.trim(); lfmError = null }, stringResource(R.string.scrobble_api_secret), password = true)
                            VyoraTextField(user, { user = it; lfmError = null }, stringResource(R.string.server_user))
                            VyoraTextField(pass, { pass = it; lfmError = null }, stringResource(R.string.server_password), password = true, error = lfmError)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VyoraAccent)
                            GlassButton(
                                stringResource(R.string.scrobble_sign_in),
                                {
                                    busy = true
                                    scope.launch {
                                        Scrobbler.loginLastFm(apiKey, secret, user.trim(), pass)
                                            .onSuccess { SettingsRepository.update { s -> s.copy(scrobbleLastFm = true) }; pass = "" }
                                            .onFailure { lfmError = failed + (it.message?.let { m -> " ($m)" } ?: "") }
                                        busy = false
                                    }
                                },
                                Modifier.fillMaxWidth(),
                                enabled = !busy && apiKey.isNotBlank() && secret.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                            )
                        }
                    }
                    SettingsNavRow(
                        title = stringResource(R.string.scrobble_get_key),
                        onClick = { openUrl(context, "https://www.last.fm/api/account/create") },
                        divider = false,
                    )
                }
            }
        }
        item(key = "listenbrainz") {
            val signedIn = accounts.listenBrainzUser
            SettingsGroup(header = "ListenBrainz", footer = if (signedIn == null) stringResource(R.string.scrobble_lb_footer) else null) {
                if (signedIn != null) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.scrobble_enable),
                        subtitle = stringResource(R.string.scrobble_signed_in_as, signedIn),
                        icon = Icons.Default.GraphicEq, iconTint = Color(0xFF353070),
                        checked = settings.scrobbleListenBrainz,
                        onCheckedChange = { v -> SettingsRepository.update { it.copy(scrobbleListenBrainz = v) } },
                    )
                    SettingsActionRow(stringResource(R.string.scrobble_sign_out), { Scrobbler.logoutListenBrainz() }, destructive = true, divider = false)
                } else {
                    SettingsControlRow(divider = false) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            VyoraTextField(lbToken, { lbToken = it.trim(); lbError = null }, stringResource(R.string.scrobble_lb_token), password = true, error = lbError)
                            GlassButton(
                                stringResource(R.string.scrobble_sign_in),
                                {
                                    busy = true
                                    scope.launch {
                                        Scrobbler.loginListenBrainz(lbToken)
                                            .onSuccess { SettingsRepository.update { s -> s.copy(scrobbleListenBrainz = true) }; lbToken = "" }
                                            .onFailure { lbError = failed }
                                        busy = false
                                    }
                                },
                                Modifier.fillMaxWidth(),
                                enabled = !busy && lbToken.isNotBlank(),
                            )
                        }
                    }
                    SettingsNavRow(
                        title = stringResource(R.string.scrobble_get_token),
                        onClick = { openUrl(context, "https://listenbrainz.org/settings/") },
                        divider = false,
                    )
                }
            }
        }
        if (pending > 0) item(key = "pending") {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.scrobble_pending, pending),
                    icon = Icons.Default.CloudUpload, iconTint = SystemColors.Gray,
                    onClick = { scope.launch { Scrobbler.flush() } },
                    divider = false,
                )
            }
        }
    }
}

/** Import a public Spotify playlist or album into a VYORA playlist. */
@Composable
fun SpotifyImportScreen(onBack: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val progress by SpotifyImport.progress.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    val running = progress is SpotifyImport.Progress.Reading || progress is SpotifyImport.Progress.Matching

    SettingsPage(title = stringResource(R.string.import_title), onBack = { if (!running) SpotifyImport.reset(); onBack() }) {
        item(key = "intro") {
            Text(
                stringResource(R.string.import_intro), color = VyoraTextMuted,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "form") {
            SettingsGroup(footer = stringResource(R.string.import_footer)) {
                SettingsControlRow(divider = false) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val error = (progress as? SpotifyImport.Progress.Failed)?.reason?.let { r ->
                            stringResource(
                                when (r) {
                                    SpotifyImport.Reason.BAD_LINK -> R.string.import_bad_link
                                    SpotifyImport.Reason.NOT_FOUND -> R.string.import_not_found
                                    SpotifyImport.Reason.EMPTY -> R.string.import_empty
                                    SpotifyImport.Reason.NETWORK -> R.string.import_network
                                }
                            )
                        }
                        VyoraTextField(
                            url, { url = it; if (!running) SpotifyImport.reset() }, stringResource(R.string.import_link),
                            placeholder = "https://open.spotify.com/playlist/…", keyboardType = KeyboardType.Uri, error = error,
                        )
                        when (val p = progress) {
                            SpotifyImport.Progress.Reading -> {
                                Text(stringResource(R.string.import_reading), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(Modifier.fillMaxWidth(), color = VyoraAccent)
                            }
                            is SpotifyImport.Progress.Matching -> {
                                Text(stringResource(R.string.import_matching, p.done, p.total), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(
                                    progress = { if (p.total == 0) 0f else p.done / p.total.toFloat() },
                                    modifier = Modifier.fillMaxWidth(), color = VyoraAccent,
                                )
                            }
                            is SpotifyImport.Progress.Done -> {
                                Text(stringResource(R.string.import_done, p.name, p.matched, p.total), color = VyoraText, style = MaterialTheme.typography.bodyMedium)
                                GlassButton(stringResource(R.string.import_open), { SpotifyImport.reset(); onOpenPlaylist(p.playlistId) }, Modifier.fillMaxWidth(), icon = Icons.Default.LibraryMusic)
                            }
                            else -> Unit
                        }
                        if (progress !is SpotifyImport.Progress.Done) {
                            GlassButton(
                                stringResource(R.string.import_start),
                                { scope.launch { SpotifyImport.import(url) } },
                                Modifier.fillMaxWidth(),
                                icon = Icons.Default.Download,
                                enabled = !running && url.isNotBlank(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Backup & restore group, embedded in Settings. */
@Composable
fun BackupGroup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.backup_saved)
    val restored = stringResource(R.string.backup_restored)
    val failed = stringResource(R.string.backup_failed)
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = Backup.export(context, uri)
            busy = false
            Toast.makeText(context, if (r.isSuccess) saved else failed, Toast.LENGTH_SHORT).show()
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = Backup.restore(context, uri)
            busy = false
            Toast.makeText(context, r.fold({ restored.format(it) }, { failed }), Toast.LENGTH_SHORT).show()
        }
    }
    SettingsGroup(header = stringResource(R.string.backup_header), footer = stringResource(R.string.backup_footer)) {
        SettingsRow(
            title = stringResource(R.string.backup_create),
            icon = Icons.Default.Backup, iconTint = SystemColors.Green,
            enabled = !busy,
            onClick = { create.launch(Backup.fileName()) },
        )
        SettingsRow(
            title = stringResource(R.string.backup_restore),
            icon = Icons.Default.Restore, iconTint = SystemColors.Orange,
            enabled = !busy, divider = false,
            onClick = { open.launch(arrayOf(Backup.MIME, "text/plain", "application/octet-stream")) },
        )
    }
}

/** Open-source licenses: VYORA's own GPL-3.0 text and the projects it builds on. */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpenText: (String) -> Unit) {
    val context = LocalContext.current
    SettingsPage(title = stringResource(R.string.licenses_title), onBack = onBack) {
        item(key = "vyora") {
            SettingsGroup(header = "VYORA", footer = stringResource(R.string.licenses_vyora_footer)) {
                SettingsNavRow(
                    title = "GNU General Public License v3.0",
                    icon = Icons.Default.Description, iconTint = SystemColors.Blue,
                    onClick = { onOpenText("GPL-3.0") },
                )
                SettingsNavRow(
                    title = stringResource(R.string.licenses_notice),
                    icon = Icons.Default.Description, iconTint = SystemColors.Gray,
                    onClick = { onOpenText("NOTICE") },
                    divider = false,
                )
            }
        }
        item(key = "third") {
            SettingsGroup(header = stringResource(R.string.licenses_third_party)) {
                ThirdParty.forEachIndexed { i, c ->
                    SettingsNavRow(
                        title = c.name,
                        subtitle = c.use,
                        value = c.license,
                        onClick = { openUrl(context, c.url) },
                        divider = i < ThirdParty.lastIndex,
                    )
                }
            }
        }
        item(key = "apache") {
            SettingsGroup {
                SettingsNavRow(
                    title = "Apache License 2.0",
                    icon = Icons.Default.Description, iconTint = SystemColors.Gray,
                    onClick = { onOpenText("Apache-2.0") },
                    divider = false,
                )
            }
        }
    }
}

@Composable
fun LicenseTextScreen(name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(name) {
        text = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/$name.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    SettingsPage(title = name, onBack = onBack) {
        item(key = "text") {
            SelectionContainer {
                Text(
                    text, color = VyoraText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private data class ThirdPartyLicense(val name: String, val license: String, val url: String, val use: String)

private val ThirdParty = listOf(
    ThirdPartyLicense("NewPipe Extractor", "GPL-3.0", "https://github.com/TeamNewPipe/NewPipeExtractor", "YouTube Music streams and metadata"),
    ThirdPartyLicense("BitChord", "GPL-3.0", "https://github.com/kushagrasinghx/BitChord", "Lossless output path detection (ported)"),
    ThirdPartyLicense("YumaPlayer / ArchiveTune", "GPL-3.0", "https://github.com/MuwMx/YumaPlayer", "Discord OAuth2 + Rich Presence, Spotify login (ported); word-synced lyrics approach"),
    ThirdPartyLicense("Convx", "GPL-3.0", "https://github.com/cosmictaserdev-creator/Convx", "Glass modifier, nav bar, mini player"),
    ThirdPartyLicense("Kyant0 / backdrop", "Apache-2.0", "https://github.com/Kyant0/backdrop", "Backdrop effects"),
    ThirdPartyLicense("Kyant0 / Capsule", "Apache-2.0", "https://github.com/Kyant0/Capsule", "Continuous corner shapes"),
    ThirdPartyLicense("compose-floating-tab-bar", "Apache-2.0", "https://github.com/elyesmansour/compose-floating-tab-bar", "Floating tab bar"),
    ThirdPartyLicense("AndroidX (Media3, Compose, Room, WorkManager…)", "Apache-2.0", "https://github.com/androidx/media", "Playback, UI, database"),
    ThirdPartyLicense("OkHttp", "Apache-2.0", "https://github.com/square/okhttp", "Networking"),
    ThirdPartyLicense("Coil", "Apache-2.0", "https://github.com/coil-kt/coil", "Image loading"),
    ThirdPartyLicense("BetterLyrics API", "Service", "https://lyrics-api.boidu.dev", "Word-synced lyrics"),
    ThirdPartyLicense("LRCLIB", "Service", "https://lrclib.net", "Line-synced lyrics"),
    ThirdPartyLicense("Subsonic API", "Spec", "https://www.subsonic.org/pages/api.jsp", "Music server streaming"),
    ThirdPartyLicense("Last.fm / ListenBrainz", "Service", "https://listenbrainz.org", "Scrobbling"),
)
