package com.vyora.music

import com.vyora.music.data.update.ReleaseApi
import com.vyora.music.data.update.UpdateSignature
import com.vyora.music.data.update.UpdateSignature.Verdict
import com.vyora.music.playback.cast.CastProxy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTest {
    private val release = UpdateSignature.RELEASE_CERT_SHA256.first()
    private val other = "00".repeat(32)

    @Test fun updateSignatureFailsClosed() {
        assertEquals(Verdict.OK, UpdateSignature.check(setOf(release), setOf(release), UpdateSignature.RELEASE_CERT_SHA256))
        // Unreadable signer info on either side is a rejection, never a pass.
        assertEquals(Verdict.UNREADABLE, UpdateSignature.check(setOf(release), emptySet(), UpdateSignature.RELEASE_CERT_SHA256))
        assertEquals(Verdict.UNREADABLE, UpdateSignature.check(emptySet(), setOf(release), UpdateSignature.RELEASE_CERT_SHA256))
        assertEquals(Verdict.MISMATCH, UpdateSignature.check(setOf(release), setOf(other), UpdateSignature.RELEASE_CERT_SHA256))
        // Same key as the installed app but not the pinned release key (e.g. a re-signed debug build).
        assertEquals(Verdict.NOT_PINNED, UpdateSignature.check(setOf(other), setOf(other), UpdateSignature.RELEASE_CERT_SHA256))
        // Debug builds skip the pin but still require a matching signer.
        assertEquals(Verdict.OK, UpdateSignature.check(setOf(other), setOf(other), null))
        // apksigner prints lower-case hex; keytool prints colon-separated upper-case.
        val colon = release.uppercase().chunked(2).joinToString(":")
        assertEquals(Verdict.OK, UpdateSignature.check(setOf(colon), setOf(release), UpdateSignature.RELEASE_CERT_SHA256))
    }

    @Test fun pinnedCertificateIsTheReleaseKey() {
        assertEquals(setOf("75a7fd6458a9590b1b0c91d09c966f1ce202aae7f97dfe6021ad070d35442d9f"), UpdateSignature.RELEASE_CERT_SHA256)
    }

    @Test fun checksumFileIsParsedAndCompared() {
        val hash = "3daaccc77c6346a381113c4148a4f8b6d9254283bafbb811694db0c8fb00f3c7"
        assertEquals(hash, UpdateSignature.parseChecksum("$hash  VYORA-1.6.0-release.apk\n"))
        assertEquals(hash, UpdateSignature.parseChecksum(hash.uppercase()))
        assertNull(UpdateSignature.parseChecksum("not a hash"))
        assertNull(UpdateSignature.parseChecksum(""))
        val f = File.createTempFile("vyora", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateSignature.sha256(f))
    }

    @Test fun releaseExposesItsChecksumAssetAndRejectsPlainHttp() {
        val json = """
            {"tag_name":"v1.7.0","assets":[
              {"name":"VYORA-1.7.0-release.apk","browser_download_url":"https://example.com/a.apk","size":9},
              {"name":"VYORA-1.7.0-release.apk.sha256","browser_download_url":"https://example.com/a.apk.sha256","size":1}]}
        """.trimIndent()
        assertEquals("https://example.com/a.apk.sha256", ReleaseApi.parse(json)?.checksumUrl)
        val plain = json.replace("https://example.com/a.apk\"", "http://example.com/a.apk\"")
        assertNull(ReleaseApi.parse(plain))
        val noSum = """{"tag_name":"v1.7.0","assets":[{"name":"x.apk","browser_download_url":"https://e/x.apk","size":1}]}"""
        assertNull(ReleaseApi.parse(noSum)?.checksumUrl)
    }

    @Test fun castProxyServesOnlyVideoIdsAndLocalFiles() {
        assertTrue(CastProxy.isServable("dQw4w9WgXcQ"))
        assertTrue(CastProxy.isServable("local:12345"))
        assertFalse(CastProxy.isServable("local:../../etc"))
        assertFalse(CastProxy.isServable("local:"))
        assertFalse(CastProxy.isServable("../secret"))
        assertEquals("audio/flac", CastProxy.sniff("fLaC\u0000\u0000".toByteArray()))
        assertEquals("audio/wav", CastProxy.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
    }
}
