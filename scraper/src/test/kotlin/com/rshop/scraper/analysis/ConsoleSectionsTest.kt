package com.rshop.scraper.analysis

import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.website.WebsiteSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sites where you first pick a console, then browse its (paginated) games, with a search form. */
class ConsoleSectionsTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    @Test
    fun `console index is detected as sections`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "consoles")

        val sections = analysis.config.sections!!
        assertEquals(listOf("div.console-tile"), sections.item)
        assertEquals("page/{page}/", sections.pageSuffix)
        assertEquals(listOf("NES", "Game Boy", "Mega Drive"), analysis.consoles)
        // Sample comes from the first console; its name becomes the platform.
        assertEquals("Neon Drift", analysis.sampleGames.first().title)
        assertEquals("NES", analysis.sampleGames.first().platform)
    }

    @Test
    fun `crawl walks every console and its pages`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "consoles")
        val pages = WebsiteSource(analysis.config, site.fetcher()).crawl().toList()

        // One page of each console per round: every console gets games before NES page 2.
        assertEquals(listOf("NES", "Game Boy", "Mega Drive", "NES"), pages.map { it.section })
        val games = pages.flatMap { it.games }
        assertEquals(10, games.size)
        assertEquals("NES", games.single { it.title == "Rocket Rush" }.platform)
        assertEquals("Mega Drive", games.single { it.title == "Turbo Beat" }.platform)
    }

    @Test
    fun `the analysis lists the consoles with their pages`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "consoles")

        assertEquals(listOf("NES", "Game Boy", "Mega Drive"), analysis.sections.map { it.name })
        assertEquals(true, analysis.sections.all { it.url.startsWith(site.baseUrl) })
    }

    @Test
    fun `only the chosen consoles are crawled`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "consoles")
        val chosen = analysis.sections.filter { it.name != "Game Boy" }.map { it.url }
        site.requests.clear()

        val config = analysis.config.copy(enabledSections = chosen)
        val pages = WebsiteSource(config, site.fetcher()).crawl().toList()

        assertEquals(listOf("NES", "Mega Drive", "NES"), pages.map { it.section })
        assertEquals(false, site.requests.any { it.startsWith("/console/gb") })
    }

    @Test
    fun `no restriction reads every console and an empty choice is refused`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "consoles")
        assertEquals(null, analysis.config.enabledSections)

        val problems = runCatching { analysis.config.copy(enabledSections = emptyList()).validate() }.exceptionOrNull()
        assertEquals(true, problems is com.rshop.scraper.ScraperConfigException)
        // The choice survives a save and reload.
        val chosen = listOf(analysis.sections.first().url)
        val restored = com.rshop.scraper.config.ScraperConfig.fromJson(analysis.config.copy(enabledSections = chosen).toJson())
        assertEquals(chosen, restored.enabledSections)
    }

    @Test
    fun `search form is detected and usable`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "consoles")
        assertEquals("/search?type=roms&q={query}", analysis.config.searchUrl)

        val results = WebsiteSource(analysis.config, site.fetcher()).search("neon")
        assertEquals(listOf("Neon Drift"), results.map { it.title })
    }

    @Test
    fun `consoles in a navigation dropdown are followed from the home page`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl)

        val sections = analysis.config.sections!!
        assertEquals("/", sections.url)
        assertEquals("^/console/[^/?#]+/?$", sections.linkPattern)
        // Counts and "ROMs" suffixes are stripped from menu labels.
        assertEquals(listOf("NES", "Game Boy", "Mega Drive"), analysis.consoles)
        assertEquals("Neon Drift", analysis.sampleGames.first().title)

        val pages = WebsiteSource(analysis.config, site.fetcher()).crawl().toList()
        // One page of each console per round: every console gets games before NES page 2.
        assertEquals(listOf("NES", "Game Boy", "Mega Drive", "NES"), pages.map { it.section })
        assertEquals(10, pages.flatMap { it.games }.size)
        // Home, About, FAQ… are not crawled as consoles.
        assertEquals(false, site.requests.contains("/about"))
    }

    @Test
    fun `a Consoles link is followed to find the console index`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "news")

        assertEquals("/consoles", analysis.config.sections!!.url)
        assertEquals(listOf("NES", "Game Boy", "Mega Drive"), analysis.consoles)
        assertEquals("NES", analysis.sampleGames.first().platform)
    }

    @Test
    fun `a paginated listing stays in listing mode`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games")
        assertEquals(null, analysis.config.sections)
    }
}
