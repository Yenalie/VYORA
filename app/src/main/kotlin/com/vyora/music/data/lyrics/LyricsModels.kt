package com.vyora.music.data.lyrics

import org.json.JSONArray
import org.json.JSONObject

/** A separately timed piece of a word, ported from BitChord's lyric model. */
data class LyricSyllable(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int,
) {
    companion object {
        fun normalized(parts: List<LyricSyllable>, length: Int): List<LyricSyllable> {
            val usable = parts.filter { it.endChar > it.startChar && it.startChar < length }
                .sortedBy { it.startChar }
            if (usable.size < 2) return emptyList()
            return usable.mapIndexed { index, part ->
                LyricSyllable(
                    startMs = part.startMs,
                    endMs = maxOf(part.startMs, part.endMs),
                    startChar = if (index == 0) 0 else part.startChar,
                    endChar = usable.getOrNull(index + 1)?.startChar ?: length,
                )
            }
        }
    }
}

/** One word with its vocal timing; adjacent timed syllables remain grouped as one displayed word. */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val syllables: List<LyricSyllable> = emptyList(),
) {
    fun charsSungAt(positionMs: Long): Float {
        if (positionMs <= startMs) return 0f
        if (positionMs >= endMs) return text.length.toFloat()
        if (syllables.isEmpty()) {
            val span = (endMs - startMs).coerceAtLeast(1L)
            return (positionMs - startMs).toFloat() / span * text.length
        }
        for (syllable in syllables) {
            if (positionMs < syllable.startMs) return syllable.startChar.toFloat()
            if (positionMs < syllable.endMs) {
                val span = (syllable.endMs - syllable.startMs).coerceAtLeast(1L)
                return syllable.startChar + (positionMs - syllable.startMs).toFloat() / span *
                    (syllable.endChar - syllable.startChar)
            }
        }
        return text.length.toFloat()
    }
}

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val endMs: Long = -1L,
    val words: List<LyricWord> = emptyList(),
) {
    val hasWords: Boolean get() = words.isNotEmpty()

    /** Word offsets are walked forward so repeated words map to the correct occurrence. */
    val wordSpans: List<IntRange> by lazy(LazyThreadSafetyMode.NONE) {
        var offset = 0
        words.map { word ->
            val start = text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
            val end = (start + word.text.length).coerceAtMost(text.length)
            offset = end
            start until end
        }
    }

    /** Timed pieces are syllables where the provider supplied them, otherwise whole words. */
    val sweepSpans: List<IntRange> by lazy(LazyThreadSafetyMode.NONE) {
        words.flatMapIndexed { index, word ->
            val span = wordSpans[index]
            if (word.syllables.isEmpty()) listOf(span)
            else word.syllables.map { (span.first + it.startChar) until (span.first + it.endChar) }
        }
    }

    /** Fractional character index currently sung, following the BitChord word-timing model. */
    fun revealedChars(positionMs: Long): Float {
        if (words.isEmpty()) return if (positionMs >= timeMs) text.length.toFloat() else 0f
        var offset = 0
        words.forEachIndexed { index, word ->
            val start = text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
            val end = (start + word.text.length).coerceAtMost(text.length)
            if (positionMs < word.startMs) return start.toFloat()
            if (positionMs < word.endMs) return start + word.charsSungAt(positionMs)
            val next = words.getOrNull(index + 1)
            if (next != null && positionMs < next.startMs) {
                val gapStart = text.indexOf(next.text, end).takeIf { it >= 0 } ?: end
                val pause = (next.startMs - word.endMs).coerceAtLeast(1L)
                val through = (positionMs - word.endMs).toFloat() / pause
                return end + through * (gapStart - end)
            }
            offset = end
        }
        return text.length.toFloat()
    }
}

data class Lyrics(
    val synced: List<LyricLine>,
    val plain: String?,
    val source: String = "",
) {
    val isSynced: Boolean get() = synced.isNotEmpty()
    val isWordSynced: Boolean get() = synced.any { it.hasWords }
}

/** Compact JSON for the on-disk lyrics cache. Version 2 adds per-syllable timing. */
object LyricsJson {
    const val CACHE_VERSION = 2

    fun version(raw: String): Int = runCatching { JSONObject(raw).optInt("v", 0) }.getOrDefault(0)

    fun encode(l: Lyrics): String = JSONObject()
        .put("v", CACHE_VERSION)
        .put("p", l.plain ?: JSONObject.NULL)
        .put("s", l.source)
        .put("l", JSONArray().apply {
            l.synced.forEach { line ->
                put(JSONObject().put("t", line.timeMs).put("e", line.endMs).put("x", line.text).apply {
                    if (line.hasWords) put("w", JSONArray().apply {
                        line.words.forEach { word ->
                            put(JSONArray().put(word.startMs).put(word.endMs).put(word.text).put(JSONArray().apply {
                                word.syllables.forEach { part ->
                                    put(JSONArray().put(part.startMs).put(part.endMs).put(part.startChar).put(part.endChar))
                                }
                            }))
                        }
                    })
                })
            }
        })
        .toString()

    fun decode(raw: String): Lyrics? = runCatching {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("l") ?: JSONArray()
        val lines = (0 until arr.length()).map { i ->
            val line = arr.getJSONObject(i)
            val wordArray = line.optJSONArray("w")
            LyricLine(
                timeMs = line.optLong("t"),
                text = line.optString("x"),
                endMs = line.optLong("e", -1L),
                words = if (wordArray == null) emptyList() else (0 until wordArray.length()).map { j ->
                    val word = wordArray.getJSONArray(j)
                    val parts = word.optJSONArray(3)
                    LyricWord(
                        startMs = word.getLong(0),
                        endMs = word.getLong(1),
                        text = word.getString(2),
                        syllables = if (parts == null) emptyList() else (0 until parts.length()).map { k ->
                            val part = parts.getJSONArray(k)
                            LyricSyllable(part.getLong(0), part.getLong(1), part.getInt(2), part.getInt(3))
                        },
                    )
                },
            )
        }
        Lyrics(lines, if (o.isNull("p")) null else o.optString("p"), o.optString("s"))
    }.getOrNull()
}
