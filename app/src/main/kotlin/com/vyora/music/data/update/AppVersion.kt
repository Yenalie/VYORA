package com.vyora.music.data.update

data class AppVersion(val parts: List<Int>, val preRelease: String?) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (i in 0 until size) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun toString(): String =
        parts.joinToString(".") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        fun parse(raw: String?): AppVersion? {
            val clean = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
            if (clean.isEmpty()) return null
            val core = clean.substringBefore('-').substringBefore('+')
            val pre = clean.substringAfter('-', "").substringBefore('+').ifBlank { null }
            val parts = core.split('.').map { it.toIntOrNull() ?: return null }
            return AppVersion(parts, pre)
        }
    }
}
