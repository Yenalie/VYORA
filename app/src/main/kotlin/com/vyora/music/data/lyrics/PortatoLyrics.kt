package com.vyora.music.data.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Port of BitChord's ProviderLyrics/KaraokeLrc/SyllableRuns parsing path
 * (GPL-3.0; upstream commit edc1e6f36febf71a118e649411d2fc21b70ead45),
 * adapted to VYORA's Android JSON and LyricLine types.
 */
internal object PortatoLyrics {
    private val line = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val prefixWord = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
    private val suffixWord = Regex("""([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)""")
    private val wordTime = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    private val lyricContent = Regex("""LyricContent\s*=\s*\"([^\"]*)\"""", RegexOption.IGNORE_CASE)
    private val lrcLine = Regex("""(?m)^\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]""")
    private val contentKeys = listOf(
        "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
        "plainLyrics", "syncedLyrics", "line", "lines", "lyric", "data", "result", "response",
    )

    fun parse(raw: String): List<LyricLine> {
        val content = unwrap(raw) ?: return emptyList()
        val decoded = unescapeTtml(content)
        return when {
            decoded.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
                decoded.contains("http://www.w3.org/ns/ttml", ignoreCase = true) -> Ttml.parse(decoded)
            looksLikeQrc(decoded) -> parseQrc(decoded)
            lrcLine.containsMatchIn(decoded) -> LyricsRepository.parseLrc(decoded)
            else -> emptyList()
        }
    }

    private fun unwrap(raw: String): String? {
        var value = raw.replace("\uFEFF", "").trim()
        if (value.startsWith("```") ) {
            value = value.lineSequence().drop(1).toList()
                .let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
                .joinToString("\n").trim()
        }
        return extract(value, 0)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extract(value: String, depth: Int): String? {
        if (depth > 8) return null
        val trimmed = value.trim()
        // A QQ lyric row begins with [milliseconds,duration], which looks like a JSON array prefix.
        if (Regex("""^\[\d{1,8},\d{1,8}]""").containsMatchIn(trimmed)) return trimmed
        val parsed = if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
        } else null
        return when (parsed) {
            is JSONObject -> extractObject(parsed, depth + 1)
            is JSONArray -> (0 until parsed.length()).mapNotNull { i -> extractValue(parsed.opt(i), depth + 1) }
                .joinToString("\n").takeIf { it.isNotBlank() }
            is String -> extract(parsed, depth + 1) ?: parsed
            else -> if (parsed == null) trimmed else null
        }
    }

    private fun extractValue(value: Any?, depth: Int): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> extract(value, depth)
        is JSONObject -> extractObject(value, depth + 1)
        is JSONArray -> (0 until value.length()).mapNotNull { i -> extractValue(value.opt(i), depth + 1) }
            .joinToString("\n").takeIf { it.isNotBlank() }
        else -> null
    }

    private fun extractObject(obj: JSONObject, depth: Int): String? {
        if (obj.optBoolean("isError") || obj.opt("ok")?.toString() == "false") return null
        val error = obj.opt("error")
        if (error != null && error != JSONObject.NULL && error.toString() !in setOf("false", "\"\"", "")) return null
        for (key in contentKeys) {
            val result = extractValue(obj.opt(key), depth + 1)
            if (!result.isNullOrBlank()) return result
        }
        return extractValue(obj.opt("metadata"), depth + 1) ?: extractValue(obj.opt("words"), depth + 1)
    }

    private fun unescapeTtml(text: String): String = if (text.contains("&lt;tt", ignoreCase = true)) {
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
    } else text

    private fun lyricText(raw: String): String = lyricContent.find(raw)?.groupValues?.get(1)?.let { content ->
        content.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&amp;", "&")
    } ?: raw

    private fun looksLikeQrc(raw: String): Boolean = lyricText(raw).lineSequence().any { source ->
        line.matchEntire(source.trim())?.groupValues?.get(3)?.let { prefixWord.containsMatchIn(it) || suffixWord.containsMatchIn(it) } == true
    }

    private fun parseQrc(raw: String): List<LyricLine> = lyricText(raw).lineSequence().mapNotNull { source ->
        val match = line.matchEntire(source.trim()) ?: return@mapNotNull null
        val lineStart = match.groupValues[1].toLong()
        val lineDuration = match.groupValues[2].toLong()
        val body = match.groupValues[3]
        val prefixWords = wordsFromRuns(prefixWord.findAll(body).map { m ->
            timedRun(m.groupValues[3], m.groupValues[1], m.groupValues[2])
        }.toList())
        val suffixWords = wordsFromRuns(suffixWord.findAll(body).map { m ->
            timedRun(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }.toList())
        val words = if (prefixWords.sumOf { it.text.length } >= suffixWords.sumOf { it.text.length }) prefixWords else suffixWords
        if (words.isEmpty()) return@mapNotNull null
        val text = decodeEntities(body.replace(wordTime, "")).trim()
        if (text.isEmpty()) return@mapNotNull null
        LyricLine(
            timeMs = minOf(lineStart, words.first().startMs),
            text = text,
            endMs = (lineStart + lineDuration).takeIf { lineDuration > 0 } ?: -1L,
            words = words,
        )
    }.sortedBy { it.timeMs }.toList()

    private data class TimedRun(val startMs: Long, val endMs: Long, val raw: String)

    private fun timedRun(text: String, start: String, duration: String): TimedRun {
        val startMs = start.toLong()
        return TimedRun(startMs, startMs + duration.toLong(), decodeEntities(text))
    }

    /** BitChord's word boundary inference: adjacent timed syllables without whitespace form one word. */
    private fun wordsFromRuns(runs: List<TimedRun>): List<LyricWord> {
        val glue = runs.any { run -> run.raw.any(Char::isWhitespace) }
        val words = ArrayList<LyricWord>()
        val current = StringBuilder()
        val parts = ArrayList<LyricSyllable>()
        var start = 0L
        var end = 0L
        fun flush() {
            if (current.isNotEmpty()) {
                val text = current.toString()
                words += LyricWord(start, maxOf(start, end), text, LyricSyllable.normalized(parts, text.length))
            }
            current.setLength(0)
            parts.clear()
        }
        for (run in runs) {
            val content = run.raw.trim()
            if (content.isEmpty()) { flush(); continue }
            val joins = glue && current.isNotEmpty() && !run.raw.first().isWhitespace() &&
                !current.last().isUnspacedScript() && !content.first().isUnspacedScript()
            if (!joins) flush()
            if (current.isEmpty()) { start = run.startMs; end = run.startMs }
            val from = current.length
            current.append(content)
            val runEnd = maxOf(run.startMs, run.endMs)
            parts += LyricSyllable(run.startMs, runEnd, from, current.length)
            end = maxOf(end, runEnd)
            if (!glue || run.raw.last().isWhitespace()) flush()
        }
        flush()
        return words
    }

    private fun Char.isUnspacedScript(): Boolean = this in '\u3400'..'\u9FFF' || this in '\u3040'..'\u30FF' ||
        this in '\u3000'..'\u303F' || this in '\uFF00'..'\uFFEF' || this in '\u0E00'..'\u0E7F'

    private fun decodeEntities(text: String): String = text.replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
