package com.vyora.music.shots

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-night-xxhdpi")
class ScreenshotTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    private val service = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            SettingsRepository.init(app)
            if (System.getProperty("shots.glass") == "off") {
                SettingsRepository.update { it.copy(glass = it.glass.copy(enabled = false)) }
            }
            val svc = Robolectric.buildService(PlaybackService::class.java).create().get()
            val binder = svc.onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
        }
    }

    @get:Rule val chain: RuleChain = RuleChain.outerRule(service).around(compose)

    private var fakeTrack: com.vyora.music.data.youtube.SimpleItem? = null

    private fun flow(name: String): kotlinx.coroutines.flow.MutableStateFlow<Any?> {
        val f = PlayerManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(PlayerManager) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
    }

    private fun fakePlayback() {
        val item = fakeTrack ?: return
        val duration = (if (item.duration > 0) item.duration else 213L) * 1000L
        val position = (duration * 0.34).toLong()
        flow("_isBuffering").value = false
        flow("_playbackError").value = null
        flow("_isPlaying").value = true
        flow("_durationMs").value = duration
        flow("_positionMs").value = position
        flow("_progress").value = position.toFloat() / duration
    }

    private fun capture(window: android.view.Window = compose.activity.window): Bitmap {
        val view = window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var done = false
        android.view.PixelCopy.request(window, bmp, { done = true }, android.os.Handler(android.os.Looper.getMainLooper()))
        var guard = 0
        while (!done && guard++ < 50) shadowOf(android.os.Looper.getMainLooper()).idle()
        return bmp
    }

    private fun difference(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        var changed = 0
        var total = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                val d = maxOf(
                    kotlin.math.abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)),
                    kotlin.math.abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)),
                    kotlin.math.abs((p and 0xFF) - (q and 0xFF)),
                )
                if (d > 24) changed++
                total++
                x += 4
            }
            y += 4
        }
        return changed.toDouble() / total
    }

    private fun settle(minMs: Long = 1500, maxMs: Long = 30_000) {
        waitReal(minMs)
        var previous = capture()
        var stable = 0
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            waitReal(800)
            val current = capture()
            if (difference(previous, current) < 0.003) {
                if (++stable >= 3) return
            } else {
                stable = 0
            }
            previous = current
        }
        println("SHOT settle timeout")
    }

    private fun shot(name: String, minMs: Long = 1500) {
        settle(minMs)
        fakePlayback()
        compose.mainClock.advanceTimeBy(32)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val bmp = capture()
        val dir = File("/tmp/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitReal(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            fakePlayback()
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
            println("SHOT step $name failed: $e\n" + e.stackTrace.take(14).joinToString("\n"))
        }
    }

    private fun clickLastText(text: String, lowest: Boolean = true) {
        val h = compose.activity.window.decorView.height
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        val visible = nodes.withIndex().filter { (_, n) -> n.boundsInRoot.top >= 0f && n.boundsInRoot.bottom <= h && n.boundsInRoot.width > 0f }
        val pick = (if (lowest) visible.maxByOrNull { it.value.boundsInRoot.top } else visible.minByOrNull { it.value.boundsInRoot.top })!!.index
        compose.onAllNodesWithText(text, useUnmergedTree = true)[pick].performClick()
    }

    private fun clickTopDesc(desc: String) {
        val h = compose.activity.window.decorView.height
        val nodes = compose.onAllNodesWithContentDescription(desc, useUnmergedTree = true).fetchSemanticsNodes()
        val pick = nodes.withIndex().filter { (_, n) -> n.boundsInRoot.top >= 0f && n.boundsInRoot.bottom <= h && n.boundsInRoot.width > 0f }
            .minByOrNull { it.value.boundsInRoot.top }!!.index
        compose.onAllNodesWithContentDescription(desc, useUnmergedTree = true)[pick].performClick()
    }

    @Test fun tour() {
        org.junit.Assume.assumeTrue(System.getProperty("shots.only").orEmpty().isNotBlank())
        if (System.getProperty("shots.only") == "new") return newFeatures()
        if (System.getProperty("shots.only") == "v19") return v19Features()
        if (System.getProperty("shots.only") == "v110") return v110Features()
        if (System.getProperty("shots.only") == "v193") return v193Features()
        val found = runBlocking { YouTubeClient.search("Tulus", SearchFilter.SONGS).getOrThrow().items.take(8) }
        val lead = found.firstOrNull { song ->
            val lyr = runBlocking { com.vyora.music.data.lyrics.LyricsRepository.lyricsFor(song.id, song.title, song.artist, song.duration) }
            lyr != null && lyr.isSynced
        } ?: found.first()
        val songs = listOf(lead) + (found - lead)
        println("SHOT lead=${lead.title}")
        compose.runOnUiThread { songs.drop(1).take(5).forEach { com.vyora.music.data.library.LibraryManager.toggleLike(it) } }
        waitReal(6000)
        shot("01_home", minMs = 6000)
        if (System.getProperty("shots.only") == "play") return playTour(songs)
        step("library") { clickText("Library"); shot("03_library") }
        step("search") { clickText("Search"); shot("04_search") }
        step("settings") {
            clickText("Home"); waitReal(800)
            clickDesc("Settings"); shot("05_settings")
            clickText("Sign in to YouTube Music"); shot("05b_account")
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            swipeUp(); shot("06_settings_2", minMs = 1000)
            swipeUp(); shot("07_settings_3", minMs = 1000)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
        }
        playTour(songs)
    }

    private fun playTour(songs: List<com.vyora.music.data.youtube.SimpleItem>) {
        step("play") {
            SettingsRepository.update { it.copy(skipUnavailable = false) }
            compose.runOnUiThread { PlayerManager.setQueue(songs.take(6), 0) }
            fakeTrack = songs[0]
            compose.mainClock.autoAdvance = false
            shot("08_mini_player", minMs = 3000)
            clickLastText(songs[0].title); shot("09_now_playing", minMs = 2500)
            clickDesc("Queue"); shot("10_queue", minMs = 3000)
            clickDesc("Queue"); waitReal(800)
            clickTopDesc("More"); shot("11_player_menu", minMs = 1000)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(800)
        }
        step("lyrics") {
            clickDesc("Lyrics"); shot("12_lyrics", minMs = 4000)
            val song = songs[0]
            val lyr = runBlocking { com.vyora.music.data.lyrics.LyricsRepository.lyricsFor(song.id, song.title, song.artist, song.duration) }
            val texts = when {
                lyr == null -> emptyList()
                lyr.isSynced -> lyr.synced.map { it.text }
                else -> lyr.plain.orEmpty().lines()
            }.filter { it.isNotBlank() }
            println("SHOT lyrics lines=${texts.size}")
            clickDesc("Lyrics card"); waitReal(600)
            val h = compose.activity.window.decorView.height
            var picked = 0
            for (t in texts.distinct()) {
                if (picked >= 3) break
                val nodes = compose.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes()
                val index = nodes.indexOfFirst { it.boundsInRoot.top > h * 0.18f && it.boundsInRoot.bottom < h * 0.55f }
                if (index < 0) continue
                compose.onAllNodesWithText(t, useUnmergedTree = true)[index].performClick()
                waitReal(300)
                picked++
            }
            println("SHOT picked=$picked")
            shot("13_lyrics_select", minMs = 800)
            clickText("Cancel"); waitReal(500)
            clickDesc("Lyrics"); waitReal(800)
        }
        step("artist") {
            clickTopDesc("More"); waitReal(1000)
            clickText("Go to Artist"); shot("14_artist", minMs = 6000)
        }
    }

    /** `-Dshots.only=v110`: Apple-style search, suggestions and Top Results, and the new audio pages. */
    private fun v110Features() {
        waitReal(5000)
        step("search") {
            clickTopDesc("Search"); shot("50_search", minMs = 1500)
            swipeUp(); shot("51_search_2", minMs = 1000)
            val field = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0]
            field.performClick()
            field.performTextInput("tulus")
            shot("52_suggestions", minMs = 2500)
            field.performImeAction()
            shot("53_top_results", minMs = 6000)
            swipeUp(); shot("54_top_results_2", minMs = 1500)
            clickLastText("Songs", lowest = false); shot("55_songs", minMs = 4000)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(800)
        }
        step("settings") {
            runCatching { clickText("Cancel") }; waitReal(1000)
            clickText("Home"); waitReal(1500)
            clickDesc("Settings"); waitReal(1500)
            shot("55b_settings_top", minMs = 1000)
            fun reach(text: String) {
                repeat(4) {
                    if (runCatching { clickLastText(text) }.isSuccess) return
                    compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.7f, endY = bottom * 0.45f, durationMillis = 400) }
                    waitReal(700)
                }
                error("not found: $text")
            }
            reach("Audio Quality"); shot("56_audio_quality", minMs = 1200)
            clickLastText("Wi-Fi"); shot("57_quality_wifi", minMs = 1200)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(800)
            clickLastText("Download Quality"); shot("58_download_quality", minMs = 1200)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(800)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            shot("59_settings_audio", minMs = 1000)
            reach("Audio Output"); shot("60_output", minMs = 1200)
            compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.6f, endY = bottom * 0.45f, durationMillis = 400) }
            shot("61_output_2", minMs = 1000)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            swipeUp(); waitReal(600)
            shot("62_settings_playback", minMs = 1000)
            reach("Downloads"); shot("63_downloads", minMs = 1200)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
        }
        step("artist") {
            clickTopDesc("Search"); waitReal(1000)
            val field = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0]
            field.performClick(); field.performTextInput("tulus"); field.performImeAction()
            waitReal(6000)
            clickLastText("Artist", lowest = false); shot("64_artist", minMs = 6000)
        }
    }

    /** `-Dshots.only=v19`: Discord and Spotify sign-in pages, Library and the Now Playing speed control. */
    private fun v19Features() {
        val found = runBlocking { YouTubeClient.search("Tulus", SearchFilter.SONGS).getOrThrow().items.take(6) }
        waitReal(5000)
        step("settings") {
            clickDesc("Settings"); shot("40_settings", minMs = 1500)
            swipeUp(); shot("41_settings_2", minMs = 1000)
            swipeUp(); shot("42_settings_3", minMs = 1000)
            clickLastText("Spotify"); shot("43_spotify", minMs = 1500)
            clickLastText("Sign in with Spotify"); shot("44_spotify_login", minMs = 1500)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(800)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            repeat(3) { compose.onRoot().performTouchInput { swipeDown(startY = bottom * 0.25f, endY = bottom * 0.85f, durationMillis = 300) }; waitReal(400) }
            clickLastText("Appearance"); shot("45_appearance", minMs = 1200)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            clickLastText("Audio Quality"); shot("46_quality", minMs = 1200)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            clickLastText("Song Transitions"); shot("47_transitions", minMs = 1200)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            swipeUp(); waitReal(600)
            clickLastText("Storage and privacy"); shot("48_storage", minMs = 1200)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1000)
        }
        step("player") {
            SettingsRepository.update { it.copy(skipUnavailable = false, playbackSpeed = 1.25f) }
            compose.runOnUiThread { PlayerManager.setQueue(found, 0) }
            fakeTrack = found[0]
            compose.mainClock.autoAdvance = false
            shot("33_mini", minMs = 3000)
            clickLastText(found[0].title); shot("34_now_playing", minMs = 2500)
            compose.onAllNodesWithText("1.25×", useUnmergedTree = true)[0].performClick(); shot("35_speed", minMs = 1200)
        }
    }

    /** `-Dshots.only=new`: Lossless, Recap, the quality badge and the opening transition. */
    private fun newFeatures() {
        val found = runBlocking { YouTubeClient.search("Tulus", SearchFilter.SONGS).getOrThrow().items.take(8) }
        val formats = listOf(
            com.vyora.music.data.local.AudioFormatInfo("audio/flac", 96_000, 24),
            com.vyora.music.data.local.AudioFormatInfo("audio/alac", 192_000, 24),
            com.vyora.music.data.local.AudioFormatInfo("audio/flac", 44_100, 16),
            com.vyora.music.data.local.AudioFormatInfo("audio/mpeg", 44_100, 0, 320_000),
            com.vyora.music.data.local.AudioFormatInfo("audio/wav", 48_000, 24),
            com.vyora.music.data.local.AudioFormatInfo("audio/flac", 44_100, 16),
        )
        val local = found.take(6).mapIndexed { i, song ->
            com.vyora.music.data.local.LocalTrack(
                item = song.copy(id = com.vyora.music.data.youtube.SimpleItem.localId(1000L + i), url = ""),
                album = "Manusia", sizeBytes = 40_000_000L, format = formats[i], dateAdded = 0L,
            )
        }
        com.vyora.music.data.local.LocalLibrary.replaceForTest(local)
        val now = System.currentTimeMillis()
        compose.runOnUiThread {
            found.forEachIndexed { i, song ->
                repeat(8 - i) { k ->
                    com.vyora.music.data.library.LibraryManager.recordPlay(song, 150_000L + k * 9_000L, now - (k * 7 + i) * 3_600_000L * 5)
                }
            }
        }
        waitReal(6000)
        shot("20_home", minMs = 4000)
        step("library") { clickText("Library"); shot("21_library") }
        step("lossless") {
            clickText("Lossless"); shot("22_lossless", minMs = 2500)
            clickText("All 6"); shot("22b_lossless_all", minMs = 1200)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1200)
        }
        step("recap") {
            clickText("Recap"); shot("23_recap", minMs = 3000)
            swipeUp(); shot("24_recap_2", minMs = 1500)
            compose.activity.onBackPressedDispatcher.onBackPressed(); waitReal(1200)
        }
        step("player") {
            SettingsRepository.update { it.copy(skipUnavailable = false) }
            compose.runOnUiThread { PlayerManager.setQueue(local.map { it.item }, 0) }
            fakeTrack = local[0].item
            compose.mainClock.autoAdvance = false
            waitReal(2500)
            flow("_audioFormat").value = local[0].format.copy(channels = 2)
            clickLastText(local[0].item.title); shot("25_player_hires", minMs = 2500)
            flow("_audioFormat").value = local[0].format.copy(channels = 2)
            clickLastText("Hi-Res Lossless"); settle(1200)
            // AlertDialog lives in its own window: shoot the activity, dim it, and lay the dialog window on top.
            val dialog = checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()) { "format dialog did not open" }
            check(dialog.isShowing) { "format dialog is not showing" }
            val dialogWindow = dialog.window!!
            fakePlayback(); shadowOf(android.os.Looper.getMainLooper()).idle()
            val base = capture()
            val top = capture(dialogWindow)
            val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
            android.graphics.Canvas(bmp).apply {
                drawColor(0x99000000.toInt())
                drawBitmap(top, ((bmp.width - top.width) / 2).toFloat(), ((bmp.height - top.height) / 2).toFloat(), null)
            }
            File("/tmp/shots/26_format_dialog.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** `-Dshots.only=v193`: full-bleed artwork and the BitChord quality label. */
    private fun v193Features() {
        val found = runBlocking { YouTubeClient.search("Tulus", SearchFilter.SONGS).getOrThrow().items.take(6) }
        waitReal(4000)
        step("player") {
            SettingsRepository.update { it.copy(skipUnavailable = false, fullBleedArtwork = true) }
            compose.runOnUiThread { PlayerManager.setQueue(found, 0) }
            fakeTrack = found[0]
            compose.mainClock.autoAdvance = false
            waitReal(2500)
            flow("_audioFormat").value = com.vyora.music.data.local.AudioFormatInfo("audio/opus", 48_000, 0, 160_000, 2)
            clickLastText(found[0].title); shot("60_hero_high", minMs = 3000)
            flow("_audioFormat").value = com.vyora.music.data.local.AudioFormatInfo("audio/flac", 96_000, 24, 0, 2)
            shot("61_hero_hires", minMs = 1500)
            SettingsRepository.update { it.copy(fullBleedArtwork = false) }
            flow("_audioFormat").value = com.vyora.music.data.local.AudioFormatInfo("audio/mp4a-latm", 44_100, 0, 128_000, 2)
            shot("62_card_aac", minMs = 2000)
        }
    }
}
