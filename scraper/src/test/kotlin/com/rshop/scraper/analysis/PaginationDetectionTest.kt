package com.rshop.scraper.analysis

import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.html
import com.rshop.scraper.website.WebsiteSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sites whose catalogue spans several pages without a plain "Next" link. */
class PaginationDetectionTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    private fun card(slug: String) = """
        <div class="rom-item"><a href="/rom/$slug"><img src="/c/$slug.png" alt="$slug"></a>
        <h3><a href="/rom/$slug">Game $slug</a></h3><span class="size">1 MB</span></div>
    """

    private fun listing(slugs: List<String>, pager: String = "") =
        html("<html><head><title>Roms - Test</title></head><body><div class='list'>${slugs.joinToString("") { card(it) }}</div>$pager</body></html>")

    private val page1 = (1..4).map { "a$it" }
    private val page2 = (1..4).map { "b$it" }
    private val page3 = (1..2).map { "c$it" }

    @Test
    fun `numbered links without next are followed`() = runTest {
        val pager = "<div class='pages'><span>1</span> <a href='/roms?pg=2'>2</a> <a href='/roms?pg=3'>3</a></div>"
        site.routes["/roms"] = { listing(page1, pager) }
        site.routes["/roms?pg=2"] = { listing(page2, pager) }
        site.routes["/roms?pg=3"] = { listing(page3, pager) }

        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "roms")
        assertEquals(PaginationKind.Template, analysis.pagination)
        assertEquals("/roms?pg={page}", analysis.config.listUrl)

        val games = WebsiteSource(analysis.config, site.fetcher()).crawl().toList().flatMap { it.games }
        // Page 4 does not exist (404): the crawl ends cleanly instead of failing.
        assertEquals(10, games.distinctBy { it.id }.size)
    }

    @Test
    fun `next link labelled with an arrow and aria label`() = runTest {
        site.routes["/roms"] = { listing(page1, "<a aria-label='Next page' href='/roms/2'><span>→</span></a>") }
        site.routes["/roms/2"] = { listing(page2) }

        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "roms")
        assertEquals(PaginationKind.Template, analysis.pagination)
        assertEquals("/roms/{page}", analysis.config.listUrl)
    }

    @Test
    fun `pages without any link are found by probing common patterns`() = runTest {
        site.routes["/catalog"] = { listing(page1) }
        site.routes["/catalog?page=2"] = { listing(page2) }
        site.routes["/catalog?page=3"] = { listing(page3) }

        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "catalog")
        assertEquals(PaginationKind.Template, analysis.pagination)
        assertEquals("/catalog?page={page}", analysis.config.listUrl)

        val games = WebsiteSource(analysis.config, site.fetcher()).crawl().toList().flatMap { it.games }
        assertEquals(10, games.size)
    }

    @Test
    fun `a site that really has one page stays single page`() = runTest {
        site.routes["/catalog"] = { listing(page1) }
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "catalog")
        assertEquals(PaginationKind.None, analysis.pagination)
        assertEquals(1, analysis.config.maxPages)
    }

    @Test
    fun `remote search follows result pages`() = runTest {
        val form = "<form action='/find'><input name='q'></form>"
        site.routes["/catalog"] = { html("<html><head><title>Roms - Test</title></head><body>$form<div>${page1.joinToString("") { card(it) }}</div></body></html>") }
        site.routes["/find?q=game"] = { listing(page1, "<a rel='next' href='/find?q=game&amp;page=2'>Next</a>") }
        site.routes["/find?q=game&page=2"] = { listing(page2) }

        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "catalog")
        val results = WebsiteSource(analysis.config, site.fetcher()).search("game")
        assertEquals(8, results.size)
    }

    private suspend fun crawlAll(path: String): Pair<SiteAnalysis, List<String>> {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + path)
        val games = WebsiteSource(analysis.config, site.fetcher()).crawl().toList().flatMap { it.games }
        return analysis to games.map { it.id }.distinct()
    }

    @Test
    fun `an icon-only next button is followed`() = runTest {
        val pager = "<div class='pagination'><a href='/roms?p=2'><i class='fa fa-angle-right'></i></a></div>"
        site.routes["/roms"] = { listing(page1, pager) }
        site.routes["/roms?p=2"] = { listing(page2) }

        val (analysis, ids) = crawlAll("roms")
        assertEquals(PaginationKind.Template, analysis.pagination)
        assertEquals(8, ids.size)
    }

    @Test
    fun `the link after the active page is the next page`() = runTest {
        // No "next" button, and the pager is a list: 1 (active) 2 3.
        val pager = "<ul class='pagination'><li class='page-item active'><span>1</span></li>" +
            "<li class='page-item'><a href='/roms/part-2'>Two</a></li><li class='page-item'><a href='/roms/part-3'>Three</a></li></ul>"
        site.routes["/roms"] = { listing(page1, pager) }
        site.routes["/roms/part-2"] = { listing(page2, pager.replace("page-item active", "page-item")) }

        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "roms")
        assertEquals(PaginationKind.NextLink, analysis.pagination)
        val games = WebsiteSource(analysis.config, site.fetcher()).getPage(0)
        assertEquals(true, games.hasNext)
    }

    @Test
    fun `a carousel arrow before the pager does not hide the next link`() = runTest {
        val carousel = "<a class='carousel-control-next' href='#slides'>&rsaquo;</a>"
        val pager = "<a class='next page-numbers' href='/roms/page/2/'>Next</a>"
        site.routes["/roms"] = { listing(page1, carousel + pager) }
        site.routes["/roms/page/2/"] = { listing(page2, carousel) }

        val (analysis, ids) = crawlAll("roms")
        assertEquals(PaginationKind.Template, analysis.pagination)
        assertEquals(8, ids.size)
    }

    @Test
    fun `page two found through its label or a span`() = runTest {
        val pager = "<div class='pager'><a href='/roms?page=2' aria-label='Page 2'><span>2</span></a></div>"
        site.routes["/roms"] = { listing(page1, pager) }
        site.routes["/roms?page=2"] = { listing(page2) }

        val (analysis, ids) = crawlAll("roms")
        assertEquals("/roms?page={page}", analysis.config.listUrl)
        assertEquals(8, ids.size)
    }

    @Test
    fun `WordPress style paged parameter is found by probing`() = runTest {
        site.routes["/roms"] = { listing(page1) }
        site.routes["/roms?paged=2"] = { listing(page2) }

        val (analysis, ids) = crawlAll("roms")
        assertEquals("/roms?paged={page}", analysis.config.listUrl)
        assertEquals(8, ids.size)
    }

    @Test
    fun `next links in other languages are recognised`() = runTest {
        for (word in listOf("Siguiente", "Weiter", "Volgende", "Avanti &raquo;", "Suivant &rsaquo;")) {
            site.routes["/roms"] = { listing(page1, "<a class='nav' href='/roms/page/2/'>$word</a>") }
            site.routes["/roms/page/2/"] = { listing(page2) }
            val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "roms")
            assertEquals("no pagination for '$word'", PaginationKind.Template, analysis.pagination)
        }
    }
}
