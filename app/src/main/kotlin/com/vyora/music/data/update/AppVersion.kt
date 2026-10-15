package com.vyora.music.data.update

/**
 * Release version as written in a git tag or versionName: "v1.5.0", "1.5", "1.5.0-beta2".
 *
 * Numeric parts are compared one by one (missing parts count as 0). A pre-release suffix
 * ("-beta2") sorts before the same version without one, so 1.5.0-beta2 < 1.5.0.
 */
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
        /** Null when [raw] has no leading number (e.g. "nightly"). */
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
