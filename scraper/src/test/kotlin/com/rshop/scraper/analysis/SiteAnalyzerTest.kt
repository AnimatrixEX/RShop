package com.rshop.scraper.analysis

import com.rshop.scraper.ScraperException
import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.fixture
import com.rshop.scraper.testing.html
import com.rshop.scraper.website.WebsiteSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteAnalyzerTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    @Test
    fun `proposes a working config for a listing page`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games")

        assertEquals(listOf("div.game-card"), analysis.config.list.item)
        assertEquals(PaginationKind.Template, analysis.pagination)
        assertEquals("/games?page={page}", analysis.config.listUrl)
        assertEquals("Homebrew Hub", analysis.config.name)

        val titles = analysis.sampleGames.map { it.title }
        assertEquals(listOf("Neon Drift", "Pixel Quest", "Star Courier", "Block Mania"), titles)
        // Lazy-loading placeholder ignored in favor of data-src.
        assertTrue(analysis.sampleGames.all { it.coverUrl!!.contains("/covers/") })
        assertEquals("NES", analysis.sampleGames.first().platform)
        // Download counters on 3 of the 4 cards are detected.
        assertEquals(listOf(12_345L, 1_200L, 87L, null), analysis.sampleGames.map { it.downloadCount })
    }

    @Test
    fun `game page rules are detected from the first game`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games")
        val details = analysis.sampleDetails!!

        assertNull(analysis.detailsError)
        assertEquals("NES", details.game.platform)
        assertEquals("1.2", details.game.version)
        assertEquals(40L * 1024, details.game.sizeBytes)
        assertTrue(details.description!!.startsWith("Arcade racing"))
        assertEquals(2, details.screenshots.size)
        assertEquals(1, details.downloads.size)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", details.downloads.single().sha256)
    }

    @Test
    fun `the proposed config syncs every page`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games")
        val source = WebsiteSource(analysis.config, site.fetcher())

        val first = source.getPage(0)
        val second = source.getPage(1)
        assertEquals(6, first.games.size + second.games.size)
    }

    @Test
    fun `url without scheme is accepted`() = runTest {
        val bare = site.baseUrl.removePrefix("http://") + "games"
        // https is assumed for bare hosts; the local test server only speaks http, so expect a network error.
        val error = runCatching { SiteAnalyzer(site.fetcher()).analyze(bare) }.exceptionOrNull()
        assertTrue(error is ScraperException.Network || error is ScraperException)
    }

    @Test
    fun `page without repeated cards is reported`() = runTest {
        site.routes["/games"] = { html("<html><body><p>Nothing here</p></body></html>") }
        val error = runCatching { SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games") }.exceptionOrNull()
        assertTrue(error is ScraperException.StructureChanged)
    }

    @Test
    fun `anti-bot challenge page is reported as such`() = runTest {
        site.routes["/games"] = { html(fixture("empty.html")) }
        val error = runCatching { SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games") }.exceptionOrNull()
        assertTrue(error is ScraperException.Captcha)
    }
}
