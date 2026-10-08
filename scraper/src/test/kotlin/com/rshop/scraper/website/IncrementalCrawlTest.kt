package com.rshop.scraper.website

import com.rshop.scraper.analysis.SiteAnalyzer
import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.html
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A source scanned once is not read again from end to end: known games end a listing early. */
class IncrementalCrawlTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    private fun card(slug: String) = """
        <div class="rom-item"><a href="/rom/$slug"><img src="/c/$slug.png" alt="$slug"></a>
        <h3><a href="/rom/$slug">Game $slug</a></h3><span class="size">1 MB</span></div>
    """

    private fun listing(slugs: List<String>, next: String?) = html(
        "<html><head><title>Roms - Test</title></head><body><div class='list'>${slugs.joinToString("") { card(it) }}</div>" +
            (next?.let { "<a rel='next' href='$it'>Next</a>" } ?: "") + "</body></html>",
    )

    @Test
    fun `known pages after the first are not read`() = runTest {
        val requested = mutableListOf<String>()
        site.routes["/roms"] = { requested += "1"; listing(listOf("new1", "old1", "old2"), "/roms/2") }
        site.routes["/roms/2"] = { requested += "2"; listing(listOf("old3", "old4", "old5"), "/roms/3") }
        site.routes["/roms/3"] = { requested += "3"; listing(listOf("old6", "old7", "old8"), null) }

        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "roms")
        requested.clear()
        val source = WebsiteSource(analysis.config, site.fetcher())

        // Everything on the later pages is known: the first page is read (it has a new game), then the crawl stops.
        val pages = source.crawl { id -> id.contains("old") }.toList()
        assertEquals(1, pages.size)
        assertEquals(setOf("1", "2"), requested.toSet())
        assertEquals(false, source.crawlTruncated)
    }

    @Test
    fun `a full crawl still reads every page`() = runTest {
        site.routes["/roms"] = { listing(listOf("a1", "a2", "a3", "a4"), "/roms/2") }
        site.routes["/roms/2"] = { listing(listOf("b1", "b2", "b3", "b4"), "/roms/3") }
        site.routes["/roms/3"] = { listing(listOf("c1"), null) }

        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "roms")
        val games = WebsiteSource(analysis.config, site.fetcher()).crawl().toList().flatMap { it.games }
        assertEquals(9, games.distinctBy { it.id }.size)
    }
}
