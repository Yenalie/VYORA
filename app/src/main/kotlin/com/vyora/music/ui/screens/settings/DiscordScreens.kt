package com.vyora.music.ui.screens.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vyora.music.R
import com.vyora.music.data.discord.DiscordAccount
import com.vyora.music.data.discord.DiscordAuthState
import com.vyora.music.data.discord.DiscordPresence
import com.vyora.music.data.discord.DiscordStatus
import com.vyora.music.data.settings.DiscordActivityKind
import com.vyora.music.data.settings.DiscordOnlineStatus
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.playback.PlayerManager
import com.vyora.music.ui.design.GlassButton
import com.vyora.music.ui.theme.SystemColors
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraSeparator
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted

private val DiscordBlurple = Color(0xFF5865F2)
private val DiscordCard = Color(0xFF232428)
private val DiscordCardText = Color(0xFFF2F3F5)
private val DiscordCardMuted = Color(0xFFB5BAC1)

@Composable
fun DiscordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsRepository.settings.collectAsState()
    val user by DiscordAccount.user.collectAsState()
    val auth by DiscordAccount.auth.collectAsState()
    val status by DiscordPresence.status.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    val signedIn = stringResource(R.string.discord_signed_in)
    val noBrowser = stringResource(R.string.discord_no_browser)

    // The browser hands the code back through DiscordOAuthCallbackActivity; react once the account appears.
    var hadUser by remember { mutableStateOf(user != null) }
    LaunchedEffect(user) {
        if (user != null && !hadUser) {
            SettingsRepository.update { it.copy(discordPresence = true) }
            DiscordPresence.refresh()
            Toast.makeText(context, signedIn, Toast.LENGTH_SHORT).show()
        }
        hadUser = user != null
    }

    fun connect() {
        if (!DiscordAccount.launchLogin(context)) Toast.makeText(context, noBrowser, Toast.LENGTH_LONG).show()
    }

    fun change(block: (com.vyora.music.data.settings.AppSettings) -> com.vyora.music.data.settings.AppSettings) {
        SettingsRepository.update(block)
        DiscordPresence.refresh()
    }

    SettingsPage(title = stringResource(R.string.discord_title), onBack = onBack) {
        item(key = "account") {
            AccountCard(
                user = user,
                auth = auth,
                status = status,
                presenceOn = settings.discordPresence,
                onConnect = ::connect,
                onCancel = DiscordAccount::cancelLogin,
            )
        }
        if (user != null) {
            item(key = "presence") {
                SettingsGroup(footer = stringResource(R.string.discord_footer)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.discord_show_presence),
                        icon = Icons.Default.Podcasts, iconTint = DiscordBlurple,
                        checked = settings.discordPresence,
                        onCheckedChange = { v ->
                            SettingsRepository.update { it.copy(discordPresence = v) }
                            if (v) DiscordPresence.refresh() else DiscordPresence.stop()
                        },
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.discord_show_paused),
                        icon = Icons.Default.Pause, iconTint = SystemColors.Orange,
                        checked = settings.discordShowPaused,
                        enabled = settings.discordPresence,
                        onCheckedChange = { v -> change { it.copy(discordShowPaused = v) } },
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.discord_buttons),
                        subtitle = stringResource(R.string.discord_buttons_sub),
                        icon = Icons.Default.Link, iconTint = SystemColors.Blue,
                        checked = settings.discordButtons,
                        enabled = settings.discordPresence,
                        onCheckedChange = { v -> change { it.copy(discordButtons = v) } },
                        divider = false,
                    )
                }
            }
            item(key = "style") {
                SettingsGroup(header = stringResource(R.string.discord_style)) {
                    SettingsControlRow(title = stringResource(R.string.discord_activity_type)) {
                        SegmentedControl(
                            options = DiscordActivityKind.entries,
                            selected = settings.discordActivity,
                            onSelect = { k -> change { it.copy(discordActivity = k) } },
                            label = { stringResource(if (it == DiscordActivityKind.LISTENING) R.string.discord_listening else R.string.discord_playing) },
                            enabled = settings.discordPresence,
                        )
                    }
                    SettingsControlRow(title = stringResource(R.string.discord_online_status), divider = false) {
                        SegmentedControl(
                            options = DiscordOnlineStatus.entries,
                            selected = settings.discordStatus,
                            onSelect = { k -> change { it.copy(discordStatus = k) } },
                            label = {
                                stringResource(
                                    when (it) {
                                        DiscordOnlineStatus.ONLINE -> R.string.discord_online
                                        DiscordOnlineStatus.IDLE -> R.string.discord_idle
                                        DiscordOnlineStatus.DND -> R.string.discord_dnd
                                    }
                                )
                            },
                            enabled = settings.discordPresence,
                        )
                    }
                }
            }
            item(key = "preview") {
                SettingsGroup(header = stringResource(R.string.discord_preview)) {
                    PresencePreview(
                        name = user?.name.orEmpty(),
                        avatar = user?.avatarUrl,
                        listening = settings.discordActivity == DiscordActivityKind.LISTENING,
                        buttons = settings.discordButtons,
                    )
                }
            }
            item(key = "manage") {
                SettingsGroup(footer = stringResource(R.string.discord_risk)) {
                    SettingsRow(
                        title = stringResource(R.string.discord_reconnect),
                        icon = Icons.Default.Refresh, iconTint = SystemColors.Green,
                        onClick = ::connect,
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.discord_sign_out),
                        destructive = true,
                        icon = Icons.AutoMirrored.Filled.Logout, iconTint = SystemColors.Red,
                        onClick = { confirmSignOut = true },
                        divider = false,
                    )
                }
            }
        }
        item(key = "note") {
            Text(
                stringResource(R.string.discord_app_note, DiscordAccount.applicationId),
                color = VyoraTextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
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
                Toast.makeText(context, R.string.discord_signed_out, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun AccountCard(
    user: com.vyora.music.data.discord.DiscordUser?,
    auth: DiscordAuthState,
    status: DiscordStatus,
    presenceOn: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(VyoraGroupedCell)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(DiscordBlurple),
            contentAlignment = Alignment.Center,
        ) {
            if (user?.avatarUrl != null) {
                AsyncImage(
                    model = user.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(76.dp).clip(CircleShape),
                )
            } else {
                Icon(Icons.Default.Headphones, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (user != null) {
            Text(user.name, color = VyoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (user.username != user.name) {
                Text("@${user.username}", color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, tint) = statusOf(status, presenceOn)
                Icon(Icons.Default.Circle, null, tint = tint, modifier = Modifier.size(9.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(stringResource(R.string.discord_connect_title), color = VyoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.discord_connect_body),
                color = VyoraTextMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            when (auth) {
                DiscordAuthState.Waiting, DiscordAuthState.Exchanging -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = DiscordBlurple, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(if (auth == DiscordAuthState.Waiting) R.string.discord_waiting_browser else R.string.discord_checking),
                            color = VyoraText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(text = stringResource(R.string.discord_open_again), onClick = onConnect)
                        GlassButton(text = stringResource(R.string.cancel), onClick = onCancel)
                    }
                }
                else -> {
                    if (auth is DiscordAuthState.Failed) {
                        Text(
                            stringResource(R.string.discord_sign_in_failed) + "\n" + auth.message,
                            color = SystemColors.Red,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(DiscordBlurple)
                            .clickable(onClick = onConnect)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Login, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.discord_sign_in), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.discord_browser_hint), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun statusOf(status: DiscordStatus, enabled: Boolean): Pair<String, Color> = when {
    !enabled -> stringResource(R.string.discord_status_off) to SystemColors.Gray
    status == DiscordStatus.CONNECTED -> stringResource(R.string.discord_status_connected) to SystemColors.Green
    status == DiscordStatus.CONNECTING -> stringResource(R.string.discord_status_connecting) to SystemColors.Orange
    status == DiscordStatus.AUTH_FAILED -> stringResource(R.string.discord_status_auth_failed) to SystemColors.Red
    status == DiscordStatus.ERROR -> stringResource(R.string.discord_status_error) to SystemColors.Red
    else -> stringResource(R.string.discord_status_idle) to SystemColors.Gray
}

/** A small mock of the profile card so the user sees what friends will see. */
@Composable
private fun PresencePreview(name: String, avatar: String?, listening: Boolean, buttons: Boolean) {
    val item by PlayerManager.currentItem.collectAsState()
    val playing by PlayerManager.isPlaying.collectAsState()
    val title = item?.title?.ifBlank { null } ?: stringResource(R.string.discord_preview_title)
    val artist = item?.artist?.ifBlank { null } ?: stringResource(R.string.discord_preview_artist)
    Column(
        Modifier
            .padding(12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DiscordCard)
            .border(1.dp, VyoraSeparator, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = avatar, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(26.dp).clip(CircleShape).background(DiscordBlurple),
            )
            Spacer(Modifier.width(8.dp))
            Text(name, color = DiscordCardText, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(if (listening) R.string.discord_preview_listening else R.string.discord_preview_playing).uppercase(),
            color = DiscordCardMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AsyncImage(
                    model = item?.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF3F4147)),
                )
                if (item != null && !playing) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(DiscordCard),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Pause, null, tint = DiscordCardText, modifier = Modifier.size(13.dp)) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("VYORA", color = DiscordCardText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(title, color = DiscordCardText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, color = DiscordCardMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item != null && !playing) {
                    Text(stringResource(R.string.discord_paused), color = DiscordCardMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Icon(if (listening) Icons.Default.Headphones else Icons.Default.SportsEsports, null, tint = DiscordCardMuted, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(buttons) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item?.isYouTube != false) PreviewButton("Listen on YouTube Music")
                PreviewButton("Get VYORA")
            }
        }
    }
}

@Composable
private fun PreviewButton(label: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF4E5058)).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = DiscordCardText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
}
