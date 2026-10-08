package com.rshop.scraper.http

import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.file
import com.rshop.scraper.testing.html
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class FetchResultTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    @Test
    fun `content disposition names are parsed and stripped of paths`() {
        assertEquals(ContentDisposition(true, "game.zip"), ContentDisposition.parse("attachment; filename=\"game.zip\""))
        assertEquals("jeu été.7z", ContentDisposition.parse("attachment; filename=\"x.7z\"; filename*=UTF-8''jeu%20%C3%A9t%C3%A9.7z").fileName)
        assertEquals("evil.zip", ContentDisposition.parse("attachment; filename=\"../../evil.zip\"").fileName)
        assertEquals("plain.iso", ContentDisposition.parse("inline; filename=plain.iso").fileName)
        assertFalse(ContentDisposition.parse("inline").attachment)
        assertNull(ContentDisposition.parse(null).fileName)
    }

    @Test
    fun `a file is identified from its headers without reading it`() = runTest {
        site.routes["/files/neon-drift.7z"] = { file("Neon Drift.7z", "application/x-7z-compressed") }
        val result = site.fetcher().open((site.baseUrl + "files/neon-drift.7z").toHttpUrl(), 1.milliseconds)

        val remote = (result as FetchResult.File).file
        assertEquals("Neon Drift.7z", remote.fileName)
        assertEquals("application/x-7z-compressed", remote.contentType)
        assertEquals(15L, remote.sizeBytes)
    }

    @Test
    fun `recent pages come from the cache`() = runTest {
        val fetcher = site.fetcher()
        val url = (site.baseUrl + "games").toHttpUrl()
        fetcher.fetch(url, 1.milliseconds)
        fetcher.fetch(url, 1.milliseconds)
        assertEquals(1, site.requests.count { it == "/games" })

        // Download pages bypass it (short-lived tokens).
        fetcher.fetch(url, 1.milliseconds, useCache = false)
        assertEquals(2, site.requests.count { it == "/games" })
    }

    @Test
    fun `too many redirects are refused`() = runTest {
        (0..15).forEach { n ->
            site.routes["/r$n"] = { mockwebserver3.MockResponse.Builder().code(302).setHeader("Location", "/r${n + 1}").build() }
        }
        site.routes["/r16"] = { html("<html><body>end</body></html>") }
        val error = runCatching { site.fetcher().fetch((site.baseUrl + "r0").toHttpUrl(), 1.milliseconds) }.exceptionOrNull()
        assertTrue(error is com.rshop.scraper.ScraperException.InvalidContent)
    }
}
