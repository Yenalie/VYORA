package com.vyora.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vyora.music.R
import com.vyora.music.core.LocaleHelper.findActivity
import com.vyora.music.data.settings.AppLanguage
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextMuted

@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val settings by SettingsRepository.settings.collectAsState()
    val context = LocalContext.current
    SettingsPage(title = stringResource(R.string.settings_language), onBack = onBack) {
        item(key = "languages") {
            SettingsGroup(footer = stringResource(R.string.language_footer)) {
                AppLanguage.entries.forEachIndexed { i, lang ->
                    SettingsChoiceRow(
                        title = languageLabel(lang),
                        // Native name stays readable whatever the current UI language is.
                        subtitle = if (lang == AppLanguage.SYSTEM) null else lang.tag.uppercase(),
                        selected = settings.language == lang,
                        divider = i < AppLanguage.entries.lastIndex,
                        onClick = {
                            if (settings.language != lang) {
                                SettingsRepository.update { it.copy(language = lang) }
                                context.findActivity()?.recreate()
                            }
                        },
                    )
                }
            }
        }
    }
}

private data class Credit(val name: String, val license: String, val url: String)

private val Credits = listOf(
    Credit("NewPipe Extractor", "GPL-3.0", "https://github.com/TeamNewPipe/NewPipeExtractor"),
    Credit("Convx (liquid glass, tab bar)", "GPL-3.0", "https://github.com/cosmictaserdev-creator/Convx"),
    Credit("Kyant0 / backdrop", "Apache-2.0", "https://github.com/Kyant0/backdrop"),
    Credit("Kyant0 / Capsule", "Apache-2.0", "https://github.com/Kyant0/Capsule"),
    Credit("AndroidX Media3", "Apache-2.0", "https://github.com/androidx/media"),
    Credit("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
    Credit("LRCLIB (lyrics)", "", "https://lrclib.net"),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        }.getOrDefault("")
    }
    val icon = remember {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    SettingsPage(title = stringResource(R.string.settings_about), onBack = onBack) {
        item(key = "hero") {
            Column(
                Modifier.fillMaxWidth().padding(top = 18.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Image(icon, null, Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)))
                    Spacer(Modifier.height(12.dp))
                }
                Text(stringResource(R.string.app_name), color = VyoraText, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.about_desc), color = VyoraTextMuted,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
            }
        }
        item(key = "info") {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.about_version),
                    trailing = { Text(version, color = VyoraTextMuted, style = MaterialTheme.typography.bodyLarge) },
                )
                SettingsNavRow(
                    title = stringResource(R.string.about_license),
                    value = "GPL-3.0",
                    onClick = { open("https://www.gnu.org/licenses/gpl-3.0.html") },
                    divider = false,
                )
            }
        }
        item(key = "credits") {
            SettingsGroup(
                header = stringResource(R.string.about_credits),
                footer = stringResource(R.string.about_disclaimer),
            ) {
                Credits.forEachIndexed { i, c ->
                    SettingsNavRow(
                        title = c.name,
                        value = c.license.ifBlank { null },
                        onClick = { open(c.url) },
                        divider = i < Credits.lastIndex,
                    )
                }
            }
        }
    }
}
