package com.vyora.music.data.update

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Fail-closed rules for accepting a downloaded update APK.
 *
 * An update is only accepted when its signer can be read and matches the signer of the app that is
 * installed right now. Release builds additionally require the signer to be one of [RELEASE_CERT_SHA256],
 * so a replaced asset on the release page can never be installed even if the OS check were bypassed.
 */
object UpdateSignature {
    /** SHA-256 of the DER-encoded release signing certificate (`apksigner verify --print-certs`). */
    val RELEASE_CERT_SHA256: Set<String> = setOf(
        "75a7fd6458a9590b1b0c91d09c966f1ce202aae7f97dfe6021ad070d35442d9f",
    )

    enum class Verdict { OK, UNREADABLE, MISMATCH, NOT_PINNED }

    fun check(installed: Set<String>, archive: Set<String>, pinned: Set<String>?): Verdict {
        val mine = installed.map(::normalize).toSet()
        val theirs = archive.map(::normalize).toSet()
        if (mine.isEmpty() || theirs.isEmpty()) return Verdict.UNREADABLE
        if (mine.intersect(theirs).isEmpty()) return Verdict.MISMATCH
        if (pinned != null && theirs.intersect(pinned.map(::normalize).toSet()).isEmpty()) return Verdict.NOT_PINNED
        return Verdict.OK
    }

    /** Lower-case hex SHA-256 of [file]. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    /** Reads the hash from a `sha256sum`-style line ("<hex>  <name>"). */
    fun parseChecksum(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun normalize(hex: String) = hex.lowercase(Locale.ROOT).replace(":", "")
}
