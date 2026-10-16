package com.vyora.music.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import coil.imageLoader
import coil.request.ImageRequest
import com.vyora.music.MainActivity
import com.vyora.music.R

/**
 * Home-screen "now playing" widget: artwork, title, artist and previous / play-pause / next. Buttons go through
 * Media3's [MediaButtonReceiver], which also starts playback (resuming the last queue) when the app is closed.
 * Plain RemoteViews keep it light: no Glance runtime, no extra process work beyond one bitmap per song.
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val p = prefs(context)
        push(context, State(p.getString(K_TITLE, null), p.getString(K_ARTIST, null), false, null), art = lastArt)
    }

    private data class State(val title: String?, val artist: String?, val playing: Boolean, val artUri: String?)

    companion object {
        private const val PREFS = "vyora_widget"
        private const val K_TITLE = "title"
        private const val K_ARTIST = "artist"

        @Volatile private var lastState: State? = null
        @Volatile private var lastArtUri: String? = null
        @Volatile private var lastArt: Bitmap? = null

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun ids(context: Context): IntArray = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
        }.getOrDefault(IntArray(0))

        /** Called by the playback service on every relevant player event; cheap when nothing changed. */
        fun update(context: Context, player: Player?) {
            if (ids(context).isEmpty()) return
            val md = player?.currentMediaItem?.mediaMetadata
            val state = State(
                title = md?.title?.toString(),
                artist = md?.artist?.toString(),
                playing = player?.playWhenReady == true && player.playbackState != Player.STATE_ENDED,
                artUri = md?.artworkUri?.toString(),
            )
            if (state == lastState) return
            lastState = state
            prefs(context).edit { putString(K_TITLE, state.title); putString(K_ARTIST, state.artist) }
            if (state.artUri != lastArtUri) {
                lastArtUri = state.artUri
                lastArt = null
                push(context, state, null)
                val uri = state.artUri ?: return
                val app = context.applicationContext
                app.imageLoader.enqueue(
                    ImageRequest.Builder(app)
                        .data(uri)
                        .size(256)
                        .allowHardware(false)
                        .target { drawable ->
                            if (lastArtUri != uri) return@target
                            lastArt = drawable.toBitmap(256, 256)
                            lastState?.let { push(app, it, lastArt) }
                        }
                        .build()
                )
            } else {
                push(context, state, lastArt)
            }
        }

        private fun push(context: Context, state: State, art: Bitmap?) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
                setTextViewText(R.id.widget_title, state.title ?: context.getString(R.string.app_name))
                setTextViewText(R.id.widget_artist, state.artist ?: context.getString(R.string.widget_idle))
                if (art != null) setImageViewBitmap(R.id.widget_art, art) else setImageViewResource(R.id.widget_art, R.drawable.ic_widget_note)
                setImageViewResource(R.id.widget_play, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                setOnClickPendingIntent(R.id.widget_root, openApp(context))
                setOnClickPendingIntent(R.id.widget_art, openApp(context))
                setOnClickPendingIntent(R.id.widget_prev, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                setOnClickPendingIntent(R.id.widget_play, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            runCatching { AppWidgetManager.getInstance(context).updateAppWidget(ids, views) }
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        @OptIn(UnstableApi::class)
        private fun mediaKey(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(context, MediaButtonReceiver::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
