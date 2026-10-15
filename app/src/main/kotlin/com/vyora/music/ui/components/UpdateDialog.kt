package com.vyora.music.ui.components

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.vyora.music.R
import com.vyora.music.data.update.ReleaseApi
import com.vyora.music.data.update.UpdateError
import com.vyora.music.data.update.UpdateManager
import com.vyora.music.data.update.UpdateState
import com.vyora.music.data.update.release
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraGroupedCell
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted

@Composable
fun UpdateDialogHost() {
    val visible by UpdateManager.prompt.collectAsState()
    val state by UpdateManager.state.collectAsState()
    val release = state.release
    if (!visible || release == null) return
    val context = LocalContext.current
    val size = if (release.apkSize > 0) Formatter.formatShortFileSize(context, release.apkSize) else null
    val downloading = state is UpdateState.Downloading

    AlertDialog(
        onDismissRequest = { if (downloading) UpdateManager.hidePrompt() else UpdateManager.dismiss(context) },
        properties = DialogProperties(dismissOnClickOutside = !downloading),
        containerColor = VyoraGroupedCell,
        titleContentColor = VyoraText,
        textContentColor = VyoraTextMuted,
        shape = RoundedCornerShape(26.dp),
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Column {
                Text(
                    if (size != null) stringResource(R.string.update_available_size, release.version.toString(), size)
                    else stringResource(R.string.update_available_body, release.version.toString()),
                    color = VyoraText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        Text(release.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        Spacer(Modifier.height(14.dp))
                        if (s.progress < 0f) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = VyoraAccent)
                        } else {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = VyoraAccent)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (s.progress < 0f) stringResource(R.string.update_downloading)
                            else stringResource(R.string.update_downloading_percent, (s.progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is UpdateState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(errorText(s.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.Downloading -> TextButton(onClick = { UpdateManager.cancelDownload() }) {
                    Text(stringResource(R.string.cancel), color = VyoraText)
                }
                is UpdateState.ReadyToInstall -> TextButton(onClick = { UpdateManager.install(context) }) {
                    Text(stringResource(R.string.update_install), color = VyoraAccent)
                }
                is UpdateState.Failed -> if (s.reason == UpdateError.SIGNATURE) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl.ifBlank { ReleaseApi.RELEASES_PAGE }))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        UpdateManager.hidePrompt()
                    }) { Text(stringResource(R.string.update_open_releases), color = VyoraAccent) }
                } else {
                    TextButton(onClick = { UpdateManager.download(context) }) {
                        Text(stringResource(R.string.update_retry), color = VyoraAccent)
                    }
                }
                else -> TextButton(onClick = { UpdateManager.download(context) }) {
                    Text(stringResource(R.string.update_now), color = VyoraAccent)
                }
            }
        },
        dismissButton = {
            if (downloading) {
                TextButton(onClick = { UpdateManager.hidePrompt() }) {
                    Text(stringResource(R.string.update_background), color = VyoraTextMuted)
                }
            } else {
                TextButton(onClick = { UpdateManager.dismiss(context) }) {
                    Text(stringResource(R.string.update_later), color = VyoraText)
                }
            }
        },
    )
}

@Composable
fun updateErrorText(reason: UpdateError): String = errorText(reason)

@Composable
private fun errorText(reason: UpdateError): String = stringResource(
    when (reason) {
        UpdateError.NETWORK -> R.string.update_error_network
        UpdateError.DOWNLOAD -> R.string.update_error_download
        UpdateError.INVALID_APK -> R.string.update_error_invalid
        UpdateError.SIGNATURE -> R.string.update_error_signature
    }
)
