package com.rshop.scraper.website

import com.rshop.scraper.analysis.SiteAnalyzer
import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.fixture
import com.rshop.scraper.testing.html
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sites that show a download page with a countdown before the file link may be used. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPageTest {

    private val site = FixtureSite().apply {
        routes["/game/neon-drift"] = { html(fixture("game-via-page.html")) }
        routes["/download/neon-drift"] = { html(fixture("download-page.html")) }
    }

    @After
    fun tearDown() = site.close()

    @Test
    fun `analysis detects the download page and its countdown`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games")

        val download = analysis.sampleDetails!!.downloads.single()
        assertTrue(download.viaPage)
        assertEquals(site.baseUrl + "download/neon-drift", download.url)
        // 5 s announced + 1 s margin.
        assertEquals(6, analysis.config.downloadPageDelaySeconds)
        assertTrue(analysis.downloadPage!!.directLinkFound)
    }

    @Test
    fun `resolving waits the announced delay then returns the file link`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games")
        val source = WebsiteSource(analysis.config, site.fetcher())

        val before = currentTime
        val file = source.resolveDownload(site.baseUrl + "download/neon-drift").url

        assertEquals(site.baseUrl + "files/neon-drift.7z", file)
        assertTrue("waited ${currentTime - before} ms", currentTime - before >= 6_000)
    }

    @Test
    fun `download page without a static link is reported, not worked around`() = runTest {
        site.routes["/download/neon-drift"] = { html("<html><body><script>makeLink()</script></body></html>") }
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games")
        val source = WebsiteSource(analysis.config, site.fetcher())

        val error = runCatching { source.resolveDownload(site.baseUrl + "download/neon-drift") }.exceptionOrNull()
        assertTrue(error is com.rshop.scraper.ScraperException.StructureChanged)
    }
}
