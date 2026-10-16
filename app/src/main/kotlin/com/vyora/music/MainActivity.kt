package com.vyora.music

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import com.vyora.music.ui.LaunchReveal
import com.vyora.music.ui.LaunchRevealBox
import com.vyora.music.ui.VyoraAppRoot
import com.vyora.music.ui.theme.VYORATheme
import com.vyora.music.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
    private companion object {
        const val SPLASH_ICON_MS = 380L
        const val SPLASH_FADE_DELAY_MS = 90L
        const val SPLASH_FADE_MS = 360L
    }

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, SettingsRepository.languageTag(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Only a cold open gets the opening transition; rotations and theme changes stay instant.
        val animateLaunch = savedInstanceState == null
        if (animateLaunch) splash.setOnExitAnimationListener(::playSplashExit) else LaunchReveal.onSplashExit()
        SettingsRepository.init(this)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val startDark = when (SettingsRepository.current.themeMode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
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
                LaunchRevealBox(animate = animateLaunch) {
                    VyoraAppRoot(settings)
                }
            }
        }
        UpdateManager.handleIntent(this, intent)
        handleVoiceSearch(intent)
        askForNotifications()
    }

    /** The waveform zooms toward the viewer and dissolves while the UI settles in underneath. */
    private fun playSplashExit(provider: SplashScreenViewProvider) {
        LaunchReveal.onSplashExit()
        runCatching {
            val icon = provider.iconView
            icon.animate()
                .scaleX(2.2f).scaleY(2.2f).alpha(0f)
                .setDuration(SPLASH_ICON_MS)
                .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.8f, 0.4f))
                .start()
            provider.view.animate()
                .alpha(0f)
                .setStartDelay(SPLASH_FADE_DELAY_MS)
                .setDuration(SPLASH_FADE_MS)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction { provider.remove() }
                .start()
        }.onFailure { provider.remove() }
    }

    private fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        val prefs = getSharedPreferences("vyora_prompts", MODE_PRIVATE)
        if (prefs.getBoolean("notifications_asked", false)) return
        prefs.edit().putBoolean("notifications_asked", true).apply()
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        UpdateManager.handleIntent(this, intent)
        handleVoiceSearch(intent)
    }

    /** Assistant voice search ("play … on VYORA"): play the best YouTube Music match, or resume if no query. */
    private fun handleVoiceSearch(intent: Intent?) {
        if (intent?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty().trim()
        intent.action = Intent.ACTION_MAIN
        lifecycleScope.launch {
            if (query.isEmpty()) {
                if (!com.vyora.music.playback.PlayerManager.isPlaying.value) com.vyora.music.playback.PlayerManager.togglePlayPause()
                return@launch
            }
            val songs = com.vyora.music.data.youtube.YouTubeClient.search(query, com.vyora.music.data.youtube.SearchFilter.SONGS)
                .getOrNull()?.items.orEmpty().filter { it.isSong }
            if (songs.isNotEmpty()) com.vyora.music.playback.PlayerManager.setQueue(songs.take(20), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.onResume(this)
        com.vyora.music.data.download.Downloads.resumePending(this)
    }

    override fun onStop() {
        super.onStop()
        SettingsRepository.flush()
    }

    private fun applySystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
