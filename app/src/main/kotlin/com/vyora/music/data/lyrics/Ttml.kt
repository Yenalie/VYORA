package com.vyora.music.data.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Port of BitChord's TTML lyric parser (GPL-3.0; upstream commit
 * edc1e6f36febf71a118e649411d2fc21b70ead45), adapted to VYORA's lyric model.
 * Apple TTML can split one spoken word across adjacent timed spans; whitespace,
 * not a span boundary, separates words. The individual span timings are retained
 * as syllables so the renderer can follow them without relayout.
 */
object Ttml {
    private val skippedRoles = setOf("x-translation", "x-roman", "x-bg")

    fun parse(ttml: String): List<LyricLine> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            harden("http://apache.org/xml/features/disallow-doctype-decl")
            harden("http://xml.org/sax/features/external-general-entities", false)
            harden("http://xml.org/sax/features/external-parameter-entities", false)
            harden(XMLConstants.FEATURE_SECURE_PROCESSING)
            runCatching { isExpandEntityReferences = false }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        val paragraphs = document.getElementsByTagName("p")
        buildList {
            for (i in 0 until paragraphs.length) {
                val paragraph = paragraphs.item(i) as? Element ?: continue
                val pieces = mutableListOf<Piece>()
                collect(paragraph, pieces)
                val words = mergeIntoWords(pieces)
                val paragraphStart = time(paragraph.getAttribute("begin")) ?: words.firstOrNull()?.startMs ?: continue
                val paragraphEnd = time(paragraph.getAttribute("end"))?.takeIf { it > paragraphStart } ?: -1L
                if (words.isNotEmpty()) {
                    add(
                        LyricLine(
                            timeMs = minOf(paragraphStart, words.first().startMs),
                            text = words.joinToString(" ") { it.text },
                            endMs = paragraphEnd,
                            words = words,
                        ),
                    )
                } else {
                    val text = paragraph.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                    if (text.isNotEmpty()) add(LyricLine(paragraphStart, text, paragraphEnd))
                }
            }
        }.sortedBy { it.timeMs }
    }.getOrDefault(emptyList())

    private fun DocumentBuilderFactory.harden(feature: String, value: Boolean = true) {
        runCatching { setFeature(feature, value) }
    }

    private fun Element.qualified(name: String): String {
        getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
        val local = name.substringAfter(':')
        val attrs = attributes ?: return ""
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i) ?: continue
            if (attr.nodeName == name || attr.nodeName == local || attr.localName == local) {
                return attr.nodeValue.orEmpty()
            }
        }
        return ""
    }

    private fun collect(node: Node, out: MutableList<Piece>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            when (val child = children.item(i)) {
                is Element -> {
                    if (child.qualified("ttm:role") in skippedRoles) continue
                    val start = time(child.getAttribute("begin"))
                    val end = time(child.getAttribute("end"))
                    if (start != null && end != null && !hasTimedChild(child)) {
                        out += Piece.Timed(child.textContent.orEmpty(), start, end)
                    } else {
                        collect(child, out)
                    }
                }
                else -> if (child.nodeType == Node.TEXT_NODE) {
                    child.textContent.orEmpty().takeIf { it.isNotEmpty() }?.let { out += Piece.Text(it) }
                }
            }
        }
    }

    private fun hasTimedChild(element: Element): Boolean {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.getAttribute("begin").isNotEmpty() || hasTimedChild(child)) return true
        }
        return false
    }

    private fun mergeIntoWords(pieces: List<Piece>): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        val current = StringBuilder()
        val syllables = mutableListOf<LyricSyllable>()
        var start = 0L
        var end = 0L
        var timed = false

        fun flush() {
            val text = current.toString().trim()
            current.setLength(0)
            if (text.isNotEmpty() && timed) {
                words += LyricWord(start, end, text, LyricSyllable.normalized(syllables, text.length))
            }
            syllables.clear()
            timed = false
        }

        pieces.forEach { piece ->
            when (piece) {
                is Piece.Text -> when {
                    piece.text.isBlank() -> flush()
                    timed -> current.append(piece.text)
                }
                is Piece.Timed -> {
                    if (piece.text.isBlank()) return@forEach
                    if (piece.text.first().isWhitespace()) flush()
                    if (current.isEmpty()) start = piece.start
                    val from = current.length
                    current.append(piece.text.trim())
                    syllables += LyricSyllable(piece.start, piece.end, from, current.length)
                    end = piece.end
                    timed = true
                    if (piece.text.last().isWhitespace()) flush()
                }
            }
        }
        flush()
        return words
    }

    /** TTML clock values: `27.395`, `1:05.20`, `1:02:03.4`, or a plain number with s/ms unit. */
    fun time(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("ms")) return raw.dropLast(2).toDoubleOrNull()?.toLong()
        val parts = raw.removeSuffix("s").split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> parts[0].toDoubleOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
            3 -> parts[0].toDoubleOrNull()?.let { h ->
                parts[1].toDoubleOrNull()?.let { m -> parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it } }
            }
            else -> null
        } ?: return null
        return (seconds * 1000).toLong()
    }

    private sealed interface Piece {
        data class Text(val text: String) : Piece
        data class Timed(val text: String, val start: Long, val end: Long) : Piece
    }
}
