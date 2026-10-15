package com.vyora.music.shots

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import com.vyora.music.MainActivity
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.youtube.SearchFilter
import com.vyora.music.data.youtube.YouTubeClient
import com.vyora.music.playback.PlayerManager
import com.vyora.music.playback.service.PlaybackService
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Not a real test: renders the real screens to PNG (/tmp/shots) so the UI can be reviewed
 * without a device. Run: ./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -Dshots.only=all
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-night-xxhdpi")
class ScreenshotTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    private val service = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            // -Dshots.glass=off renders the tour with Liquid Glass switched off.
            if (System.getProperty("shots.glass") == "off") {
                SettingsRepository.init(app)
                SettingsRepository.update { it.copy(glass = it.glass.copy(enabled = false)) }
            }
            val svc = Robolectric.buildService(PlaybackService::class.java).create().get()
            val binder = svc.onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
        }
    }

    @get:Rule val chain: RuleChain = RuleChain.outerRule(service).around(compose)

    private fun shot(name: String) {
        val dir = File("/tmp/shots").apply { mkdirs() }
        val window = compose.activity.window
        val view = window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var done = false
        android.view.PixelCopy.request(window, bmp, { done = true }, android.os.Handler(android.os.Looper.getMainLooper()))
        var guard = 0
        while (!done && guard++ < 50) shadowOf(android.os.Looper.getMainLooper()).idle()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitReal(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
    }

    private fun clickText(text: String) {
        compose.onAllNodesWithText(text, useUnmergedTree = true)[0].performClick()
    }

    private fun clickDesc(desc: String) {
        compose.onAllNodesWithContentDescription(desc, useUnmergedTree = true)[0].performClick()
    }

    private fun swipeUp() {
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.25f, durationMillis = 400) }
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            println("SHOT step $name failed: $e")
        }
    }

    /** Clicks the on-screen node with [text] that sits lowest (e.g. the mini player). */
    private fun clickLastText(text: String, lowest: Boolean = true) {
        val h = compose.activity.window.decorView.height
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        val visible = nodes.withIndex().filter { (_, n) -> n.boundsInRoot.top >= 0f && n.boundsInRoot.bottom <= h && n.boundsInRoot.width > 0f }
        val pick = (if (lowest) visible.maxByOrNull { it.value.boundsInRoot.top } else visible.minByOrNull { it.value.boundsInRoot.top })!!.index
        compose.onAllNodesWithText(text, useUnmergedTree = true)[pick].performClick()
    }

    /** Clicks the visible node with content description [desc] nearest the top. */
    private fun clickTopDesc(desc: String) {
        val h = compose.activity.window.decorView.height
        val nodes = compose.onAllNodesWithContentDescription(desc, useUnmergedTree = true).fetchSemanticsNodes()
        val pick = nodes.withIndex().filter { (_, n) -> n.boundsInRoot.top >= 0f && n.boundsInRoot.bottom <= h && n.boundsInRoot.width > 0f }
            .minByOrNull { it.value.boundsInRoot.top }!!.index
        compose.onAllNodesWithContentDescription(desc, useUnmergedTree = true)[pick].performClick()
    }

    /** Needs the network (real YouTube Music data): only with -Dshots.only=all|play. */
    @Test fun tour() {
        org.junit.Assume.assumeTrue(System.getProperty("shots.only").orEmpty().isNotBlank())
        waitReal(9000)
        shot("01_home")
        if (System.getProperty("shots.only") == "play") return playTour()
        step("home2") { swipeUp(); waitReal(2500); shot("02_home_scrolled") }
        step("library") { clickText("Library"); waitReal(1500); shot("03_library") }
        step("search") { clickText("Search"); waitReal(2500); shot("04_search") }
        step("settings") {
            clickText("Home"); waitReal(800)
            clickDesc("Settings"); waitReal(1500); shot("05_settings")
            clickText("Sign in to YouTube Music"); waitReal(1200); shot("05b_account")
            clickText("Sign in with cookies"); waitReal(800); shot("05c_cookie_dialog")
            clickText("Cancel"); waitReal(500)
            // Signed-in layout with a stand-in account (no real session, so sync reports an error).
            if (System.getProperty("shots.account") == "1") {
                compose.runOnUiThread {
                    com.vyora.music.data.account.AccountStore.save("SAPISID=test", com.vyora.music.data.account.YouTubeAccount("Yena", "@yena", null))
                }
                waitReal(3000); shot("05d_account_signed_in")
                compose.runOnUiThread { com.vyora.music.data.account.AccountStore.clear() }
            }
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            swipeUp(); waitReal(800); shot("06_settings_2")
            swipeUp(); waitReal(800); shot("07_settings_3")
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
        }
        playTour()
    }

    private fun playTour() {
        step("play") {
            // -Dshots.video=1 plays music videos (16:9 artwork) instead of songs.
            val filter = if (System.getProperty("shots.video") == "1") SearchFilter.VIDEOS else SearchFilter.SONGS
            val songs = runBlocking { YouTubeClient.search("Tulus", filter).getOrThrow().items.take(6) }
            compose.runOnUiThread { PlayerManager.setQueue(songs, 0) }
            waitReal(3000); shot("08_mini_player")
            clickLastText(songs[0].title); waitReal(2500); shot("09_now_playing")
            clickDesc("Queue"); waitReal(6000); shot("10_queue")
            clickDesc("Queue"); waitReal(800)
            clickTopDesc("More"); waitReal(1000); shot("11_player_menu")
            clickText("Sleep Timer"); waitReal(1000); shot("12_sleep_menu")
            clickText("30 minutes"); waitReal(1500); shot("13_sleep_badge")
            clickLastText(songs[0].artist, lowest = false); waitReal(9000); shot("14_artist")
            swipeUp(); waitReal(1500); shot("15_artist_2")
        }
    }
}
