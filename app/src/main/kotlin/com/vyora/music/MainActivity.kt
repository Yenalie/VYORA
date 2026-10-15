package com.vyora.music

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.vyora.music.core.LocaleHelper
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.settings.ThemeMode
import com.vyora.music.data.update.UpdateManager
import com.vyora.music.ui.VyoraAppRoot
import com.vyora.music.ui.theme.VYORATheme
import com.vyora.music.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Per-app language: wrap the base context before any resource is resolved.
        super.attachBaseContext(LocaleHelper.wrap(newBase, SettingsRepository.languageTag(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsRepository.init(this)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val startDark = when (SettingsRepository.current.themeMode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        // Window colour matches the in-app appearance before Compose draws its first frame.
        setTheme(if (startDark) R.style.Theme_Vyora_Dark else R.style.Theme_Vyora_Light)
        super.onCreate(savedInstanceState)
        applySystemBars(startDark)
        setContent {
            val settings by SettingsRepository.settings.collectAsState()
            val dark = resolveDarkTheme(settings.themeMode)
            LaunchedEffect(dark) {
                applySystemBars(dark)
                window.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                )
            }
            VYORATheme(darkTheme = dark, accent = Color(settings.accent.argb)) {
                VyoraAppRoot(settings)
            }
        }
        // Opened from the "update available" notification.
        UpdateManager.handleIntent(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        UpdateManager.handleIntent(this, intent)
    }

    override fun onResume() {
        super.onResume()
        // Returning from "Install unknown apps" with the permission now granted.
        UpdateManager.onResume(this)
    }

    override fun onStop() {
        super.onStop()
        SettingsRepository.flush()
    }

    /** Status / navigation bar icons follow the app appearance, not the system one. */
    private fun applySystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
