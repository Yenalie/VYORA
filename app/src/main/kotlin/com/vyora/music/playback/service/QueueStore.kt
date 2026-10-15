package com.vyora.music.playback.service

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.media3.common.MediaItem
import com.vyora.music.data.db.QueueEntity
import com.vyora.music.data.db.QueueStateEntity
import com.vyora.music.data.db.VyoraDatabase
import com.vyora.music.data.youtube.SimpleItemJson
import com.vyora.music.playback.MediaItems
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

object QueueStore {
    private const val TAG = "VYORA-Queue"
    private const val MAX_ITEMS = 500
    private const val LEGACY_FILE = "queue.json"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vyora-queue").apply { isDaemon = true } }

    data class Saved(val items: List<MediaItem>, val index: Int, val positionMs: Long)

    fun save(context: Context, items: List<MediaItem>, index: Int, positionMs: Long) {
        val dao = VyoraDatabase.get(context).queue()
        val rows = items.take(MAX_ITEMS).mapNotNull { media ->
            MediaItems.toSimpleItem(media)?.let { media to it }
        }.mapIndexed { i, (media, item) -> QueueEntity.of(i, item, MediaItems.isAutoplay(media)) }
        val state = QueueStateEntity(index = index.coerceIn(0, (rows.size - 1).coerceAtLeast(0)), positionMs = positionMs)
        io.execute { runCatching { dao.replace(rows, state) }.onFailure { Log.w(TAG, "Cannot save queue", it) } }
    }

    fun load(context: Context): Saved? {
        val app = context.applicationContext
        val dao = VyoraDatabase.get(app).queue()
        return runCatching {
            io.submit<Saved?> {
                importLegacy(app)
                val rows = dao.items()
                if (rows.isEmpty()) return@submit null
                val state = dao.state()
                val items = rows.map { MediaItems.from(it.toItem(), autoplay = it.autoplay) }
                Saved(items, (state?.index ?: 0).coerceIn(0, items.lastIndex), state?.positionMs ?: 0L)
            }.get()
        }.onFailure { Log.w(TAG, "Saved queue unreadable, starting empty", it) }.getOrNull()
    }

    fun clear(context: Context) {
        val dao = VyoraDatabase.get(context).queue()
        io.execute { runCatching { dao.clear() } }
    }

    private fun importLegacy(context: Context) {
        val file = File(context.filesDir, LEGACY_FILE)
        if (!file.exists()) return
        val atomic = AtomicFile(file)
        runCatching {
            val json = JSONObject(atomic.readFully().toString(Charsets.UTF_8))
            val array = json.getJSONArray("items")
            val rows = (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                SimpleItemJson.fromJson(row)?.let { it to row.optBoolean("auto", false) }
            }.mapIndexed { i, (item, auto) -> QueueEntity.of(i, item, auto) }
            if (rows.isNotEmpty()) {
                val index = json.optInt("index", 0).coerceIn(0, rows.lastIndex)
                VyoraDatabase.get(context).queue().replace(rows, QueueStateEntity(index = index, positionMs = json.optLong("pos", 0L)))
            }
        }.onFailure { Log.w(TAG, "Old queue unreadable, dropped", it) }
        atomic.delete()
    }
}
