package com.vyora.music.data.lyrics

import org.json.JSONArray
import org.json.JSONObject

/** One word (or syllable) with its own timing, for karaoke-style highlighting. */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val endMs: Long = -1L,
    val words: List<LyricWord> = emptyList(),
) {
    val hasWords: Boolean get() = words.isNotEmpty()
}

data class Lyrics(
    val synced: List<LyricLine>,
    val plain: String?,
    val source: String = "",
) {
    val isSynced: Boolean get() = synced.isNotEmpty()
    val isWordSynced: Boolean get() = synced.any { it.hasWords }
}

/** Compact JSON for the on-disk lyrics cache. */
object LyricsJson {
    fun encode(l: Lyrics): String = JSONObject()
        .put("p", l.plain ?: JSONObject.NULL)
        .put("s", l.source)
        .put("l", JSONArray().apply {
            l.synced.forEach { line ->
                put(JSONObject().put("t", line.timeMs).put("e", line.endMs).put("x", line.text).apply {
                    if (line.hasWords) put("w", JSONArray().apply {
                        line.words.forEach { w -> put(JSONArray().put(w.startMs).put(w.endMs).put(w.text)) }
                    })
                })
            }
        })
        .toString()

    fun decode(raw: String): Lyrics? = runCatching {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("l") ?: JSONArray()
        val lines = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            val w = l.optJSONArray("w")
            LyricLine(
                timeMs = l.optLong("t"),
                text = l.optString("x"),
                endMs = l.optLong("e", -1L),
                words = if (w == null) emptyList() else (0 until w.length()).map { j ->
                    val a = w.getJSONArray(j)
                    LyricWord(a.getLong(0), a.getLong(1), a.getString(2))
                },
            )
        }
        Lyrics(lines, if (o.isNull("p")) null else o.optString("p"), o.optString("s"))
    }.getOrNull()
}
