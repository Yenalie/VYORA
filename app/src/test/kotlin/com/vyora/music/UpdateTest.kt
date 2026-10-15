package com.vyora.music

import com.vyora.music.data.update.AppVersion
import com.vyora.music.data.update.ReleaseApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test fun versionsCompareNumerically() {
        assertTrue(v("v1.10.0") > v("1.9.9"))
        assertTrue(v("1.5.0") > v("1.4.1"))
        assertEquals(0, v("1.5").compareTo(v("1.5.0")))
        assertTrue(v("1.5.0") > v("1.5.0-beta2"))
        assertNull(AppVersion.parse("nightly"))
    }

    @Test fun parsesLatestRelease() {
        val json = """
            {"tag_name":"v1.5.0","name":"VYORA 1.5.0","draft":false,"prerelease":false,
             "html_url":"https://github.com/Yenalie/VYORA/releases/tag/v1.5.0",
             "body":"## VYORA 1.5.0\n\n### Yang baru\n- **Update** otomatis\n\n> Build uji coba",
             "assets":[{"name":"notes.txt","browser_download_url":"x","size":1},
                       {"name":"VYORA-1.5.0-release.apk","browser_download_url":"https://example.com/a.apk","size":8000000}]}
        """.trimIndent()
        val r = requireNotNull(ReleaseApi.parse(json))
        assertEquals("1.5.0", r.version.toString())
        assertEquals("VYORA-1.5.0-release.apk", r.apkName)
        assertEquals(8000000L, r.apkSize)
        assertEquals("VYORA 1.5.0\n\nYang baru\n• Update otomatis", r.notes)
    }

    @Test fun ignoresPreReleaseAndReleaseWithoutApk() {
        assertNull(ReleaseApi.parse("""{"tag_name":"v2.0.0","prerelease":true,"assets":[{"name":"a.apk"}]}"""))
        assertNull(ReleaseApi.parse("""{"tag_name":"v2.0.0","assets":[]}"""))
    }
}
