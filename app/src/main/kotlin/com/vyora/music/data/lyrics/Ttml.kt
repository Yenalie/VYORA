package com.vyora.music.data.lyrics

/**
 * Minimal TTML reader for Apple-Music-style lyrics (as served by BetterLyrics): `<p begin end>` per line and,
 * when the file is word-timed, `<span begin end>` per word. Background vocals (`ttm:role="x-bg"`) are skipped
 * so the main line reads cleanly. Pure Kotlin so it runs in plain JVM tests.
 */
object Ttml {
    private val paragraph = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val span = Regex("""<span\b([^>]*)>([^<]*)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val begin = Regex("""\bbegin="([^"]+)"""")
    private val end = Regex("""\bend="([^"]+)"""")
    private val tags = Regex("""<[^>]+>""")
    private val bgStart = Regex("""<span\b[^>]*ttm:role="x-bg"""")

    fun parse(ttml: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (p in paragraph.findAll(ttml)) {
            val attrs = p.groupValues[1]
            val start = begin.find(attrs)?.groupValues?.get(1)?.let(::time) ?: continue
            val stop = end.find(attrs)?.groupValues?.get(1)?.let(::time) ?: -1L
            var body = p.groupValues[2]
            bgStart.find(body)?.let { body = body.substring(0, it.range.first) }
            val words = mutableListOf<LyricWord>()
            for (m in span.findAll(body)) {
                val a = m.groupValues[1]
                val ws = begin.find(a)?.groupValues?.get(1)?.let(::time) ?: continue
                val we = end.find(a)?.groupValues?.get(1)?.let(::time) ?: ws
                val raw = unescape(m.groupValues[2])
                if (raw.isEmpty()) continue
                // A space after </span> separates words; none means the next span is a syllable of this word.
                val next = body.getOrNull(m.range.last + 1)
                val spaced = next != null && next.isWhitespace()
                words += LyricWord(ws, we, if (spaced) "$raw " else raw)
            }
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim()
            else unescape(body.replace(tags, "")).replace(Regex("""\s+"""), " ").trim()
            if (text.isEmpty()) continue
            out += LyricLine(start, text, stop, words)
        }
        return out.sortedBy { it.timeMs }
    }

    /** `12.345`, `12.3s`, `1:02.345`, `1:02:03.456` → milliseconds. */
    fun time(raw: String): Long? {
        val s = raw.trim().removeSuffix("s")
        val parts = s.split(':')
        return runCatching {
            when (parts.size) {
                1 -> (parts[0].toDouble() * 1000).toLong()
                2 -> parts[0].toLong() * 60_000 + (parts[1].toDouble() * 1000).toLong()
                3 -> parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 + (parts[2].toDouble() * 1000).toLong()
                else -> null
            }
        }.getOrNull()
    }

    fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
}
