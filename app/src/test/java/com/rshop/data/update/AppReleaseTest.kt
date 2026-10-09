package com.rshop.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppReleaseTest {

    private fun release(
        tag: String = "v0.1.5",
        url: String = "https://github.com/AnimatrixEX/RShop/releases/download/v0.1.5/app-release.apk",
        digest: String? = "\"sha256:${"ab".repeat(32)}\"",
        extra: String = "",
        asset: String = "app-release.apk",
        size: Long = 193_751_280,
    ) = """
        {"tag_name":"$tag","body":"Notes\r\nde version","draft":false,"prerelease":false$extra,
         "assets":[{"name":"$asset","size":$size,"digest":${digest ?: "null"},"browser_download_url":"$url"}]}
    """.trimIndent()

    @Test
    fun `a GitHub release is read`() {
        val r = ReleaseParser.parse(release())
        assertEquals("0.1.5", r.version)
        assertEquals("v0.1.5", r.tag)
        assertEquals("app-release.apk", r.apkName)
        assertEquals(193_751_280L, r.sizeBytes)
        assertEquals("ab".repeat(32), r.sha256)
        assertEquals("Notes\r\nde version", r.notes)
    }

    @Test
    fun `a missing or malformed digest is ignored`() {
        assertNull(ReleaseParser.parse(release(digest = null)).sha256)
        assertNull(ReleaseParser.parse(release(digest = "\"sha256:abc\"")).sha256)
    }

    @Test
    fun `only an https github link is accepted for the file`() {
        for (url in listOf("http://github.com/x/app.apk", "https://evil.example.com/app.apk", "https://github.com.evil.io/app.apk")) {
            try {
                ReleaseParser.parse(release(url = url))
                fail("accepted $url")
            } catch (e: ReleaseParseException.Invalid) {
                // expected
            }
        }
    }

    @Test
    fun `a release without an APK, or not final, is refused`() {
        try {
            ReleaseParser.parse(release(asset = "notes.txt"))
            fail()
        } catch (e: ReleaseParseException.NoApk) {
            assertEquals("v0.1.5", e.tag)
        }
        try {
            ReleaseParser.parse(release().replace("\"prerelease\":false", "\"prerelease\":true"))
            fail()
        } catch (e: ReleaseParseException.Invalid) {
            // expected
        }
        try {
            ReleaseParser.parse("not json")
            fail()
        } catch (e: ReleaseParseException.Invalid) {
            // expected
        }
    }

    @Test
    fun `versions are compared number by number`() {
        fun v(text: String) = AppVersion.parse(text)!!
        assertTrue(v("0.1.10") > v("0.1.9"))
        assertTrue(v("v1.0") > v("0.9.9"))
        assertEquals(v("1.0"), v("1.0.0"))
        assertEquals(v("0.2.0"), v("0.2.0-beta"))
        assertTrue(v("0.1.4") < v("0.1.5"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
    }
}
