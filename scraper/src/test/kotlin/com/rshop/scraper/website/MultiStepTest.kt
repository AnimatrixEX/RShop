package com.rshop.scraper.website

import com.rshop.scraper.ScraperException
import com.rshop.scraper.analysis.SiteAnalyzer
import com.rshop.scraper.config.FieldRule
import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.file
import com.rshop.scraper.testing.fixture
import com.rshop.scraper.testing.html
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sites where the file is several pages away from the catalogue: directory listings,
 * download-page chains, redirecting links, relative URLs, OpenGraph-only metadata.
 */
class MultiStepTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    private suspend fun analyzedSource() =
        WebsiteSource(SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games").config, site.fetcher())

    // --- Directory listings ----------------------------------------------------------------

    private fun serveListing() {
        site.routes["/roms/"] = { html(fixture("index-of.html")) }
        site.routes["/roms/nes/"] = { html(fixture("index-of-nes.html")) }
        site.routes["/roms/gb/"] = { html("<html><head><title>Index of /roms/gb/</title></head><body><pre><a href=\"/roms/\">Parent Directory</a></pre></body></html>") }
        site.routes["/roms/megadrive/"] = { html("<html><head><title>Index of /roms/megadrive/</title></head><body><pre><a href=\"Turbo.zip\">Turbo.zip</a></pre></body></html>") }
        site.routes["/roms/nes/Neon%20Drift.zip"] = { file("Neon Drift.zip", "application/zip") }
        site.routes["/roms/nes/Shadow_Ninja_(v1.1).7z"] = { file("Shadow_Ninja_(v1.1).7z") }
    }

    @Test
    fun `directory listing - folders are consoles, links are files`() = runTest {
        serveListing()
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "roms/")

        assertEquals(listOf("nes", "gb", "megadrive"), analysis.consoles)
        assertEquals(listOf("Neon Drift", "Shadow_Ninja_(v1.1)"), analysis.sampleGames.map { it.title })
        // No game page: the entry is its own download.
        val download = analysis.sampleDetails!!.downloads.single()
        assertEquals(site.baseUrl + "roms/nes/Neon%20Drift.zip", download.url)

        val pages = WebsiteSource(analysis.config, site.fetcher()).crawl().toList()
        assertEquals(listOf("nes", "megadrive"), pages.map { it.section })
        assertEquals("megadrive", pages.last().games.single().platform)
        // Sort links (?C=N;O=D) and the parent folder are never games.
        assertTrue(pages.flatMap { it.games }.none { "?" in it.id || it.id == "/roms/" })
    }

    // --- Download chains -------------------------------------------------------------------

    @Test
    fun `game page to download page to confirmation page to file`() = runTest {
        site.routes["/game/neon-drift"] = {
            html("<html><body><h1>Neon Drift</h1><a class=\"btn\" href=\"/download/neon-drift\">Download</a></body></html>")
        }
        site.routes["/download/neon-drift"] = {
            html("<html><body><p>Mirror 1</p><a href=\"../confirm/neon-drift?mirror=1\">Download from mirror 1</a></body></html>")
        }
        site.routes["/confirm/neon-drift?mirror=1"] = {
            html("<html><body><a href=\"/dl/42\">Download now</a></body></html>")
        }
        // Opaque link that redirects to the real file.
        site.routes["/dl/42"] = { MockResponse.Builder().code(302).setHeader("Location", "/files/neon-drift.7z").build() }

        val source = analyzedSource()
        val download = source.getGameDetails("/game/neon-drift").downloads.single()
        val info = source.resolveDownload(download.url)

        assertEquals(site.baseUrl + "dl/42", info.url)
        assertEquals("neon-drift.7z", info.fileName)
        assertEquals(site.baseUrl + "confirm/neon-drift?mirror=1", info.sourcePage)
        // The file body was not downloaded by the scraper: one request per hop, no more.
        assertEquals(1, site.requests.count { it == "/files/neon-drift.7z" })
    }

    @Test
    fun `endless download pages stop at maxDownloadHops`() = runTest {
        site.routes["/game/neon-drift"] = { html("<html><body><h1>Neon Drift</h1><a href=\"/download/1\">Download</a></body></html>") }
        (1..10).forEach { n ->
            site.routes["/download/$n"] = { html("<html><body><a href=\"/download/${n + 1}\">Download</a></body></html>") }
        }
        val source = analyzedSource()
        val before = site.requests.count { it.startsWith("/download/") }
        val error = runCatching { source.resolveDownload(site.baseUrl + "download/1") }.exceptionOrNull()

        assertTrue(error is ScraperException.StructureChanged)
        assertEquals(source.config.maxDownloadHops + 1, site.requests.count { it.startsWith("/download/") } - before)
    }

    @Test
    fun `cloudflare challenge answer is reported as captcha`() = runTest {
        site.routes["/download/neon-drift"] = {
            MockResponse.Builder().code(403).setHeader("cf-mitigated", "challenge").body("<html></html>").build()
        }
        val source = analyzedSource()
        val error = runCatching { source.resolveDownload(site.baseUrl + "download/neon-drift") }.exceptionOrNull()
        assertTrue(error is ScraperException.Captcha)
    }

    // --- URLs and metadata -----------------------------------------------------------------

    @Test
    fun `relative and absolute links resolve against the page`() {
        val document = Jsoup.parse(fixture("relative-urls.html"), "https://example.com/games/list/")
        fun link(css: String) = FieldRule.parse("$css @href").extractFirst(document)

        assertEquals("https://example.com/game/test", link("a.a"))
        assertEquals("https://example.com/games/download/test", link("a.b"))
        assertEquals("https://example.com/games/list/download/test", link("a.c"))
        assertEquals("https://example.com/download/test", link("a.d"))
    }

    @Test
    fun `opengraph alone is enough for title, description and cover`() = runTest {
        site.routes["/game/neon-drift"] = { html(fixture("metadata.html")) }
        site.routes["/files/og-game.zip"] = { file("og-game.zip", "application/zip") }
        val details = analyzedSource().getGameDetails("/game/neon-drift")

        assertEquals("Og Game", details.game.title)
        assertEquals("Described only in OpenGraph.", details.description)
        assertEquals(site.baseUrl + "covers/og-game.png", details.game.coverUrl)
        assertEquals(site.baseUrl + "files/og-game.zip", details.downloads.single().url)
    }

    // --- Limits ----------------------------------------------------------------------------

    @Test
    fun `crawl stops at maxRequestsPerCrawl`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "consoles")
        val config = analysis.config.copy(maxRequestsPerCrawl = 2)
        val pages = WebsiteSource(config, site.fetcher()).crawl().toList()

        // One request for the console index, one for the first console page.
        assertEquals(1, pages.size)
    }

    // --- Browser-like session: cookies and Referer -----------------------------------------

    @Test
    fun `new tab page sets a session cookie that Download Now requires`() = runTest {
        site.routes["/game/neon-drift"] = { html("<html><body><h1>Neon Drift</h1><a href=\"/download/neon-drift\" target=\"_blank\">Download ROM</a></body></html>") }
        site.routes["/download/neon-drift"] = {
            MockResponse.Builder()
                .setHeader("Content-Type", "text/html")
                .setHeader("Set-Cookie", "dlsession=abc; Path=/")
                .body("<html><body><p>Your file is ready.</p><a class=\"btn\" href=\"/now/neon-drift\">Download Now</a></body></html>")
                .build()
        }
        site.routes["/now/neon-drift"] = {
            val cookie = site.current?.headers?.get("Cookie").orEmpty()
            val referer = site.current?.headers?.get("Referer").orEmpty()
            if ("dlsession=abc" in cookie && referer.endsWith("/download/neon-drift")) {
                file("neon-drift.7z")
            } else {
                html("<html><body>Session expired, go back to the game page.</body></html>")
            }
        }
        val client = okhttp3.OkHttpClient.Builder().cookieJar(com.rshop.scraper.http.MemoryCookieJar()).build()
        val config = SiteAnalyzer(site.fetcher(client)).analyze(site.baseUrl + "games").config
        val source = WebsiteSource(config, site.fetcher(client))
        val download = source.getGameDetails("/game/neon-drift").downloads.single()
        val info = source.resolveDownload(download.url, referer = site.baseUrl + "game/neon-drift")

        assertEquals(site.baseUrl + "now/neon-drift", info.url)
        assertEquals("neon-drift.7z", info.fileName)
        // The downloader sends this page as Referer too.
        assertEquals(site.baseUrl + "download/neon-drift", info.sourcePage)
    }

    @Test
    fun `file on another domain is reported with its host`() = runTest {
        site.routes["/download/neon-drift"] = {
            html("<html><body><a href=\"https://files.other-host.example/neon-drift.7z\">Download Now</a></body></html>")
        }
        val source = analyzedSource()
        val error = runCatching { source.resolveDownload(site.baseUrl + "download/neon-drift") }.exceptionOrNull()

        assertTrue(error is ScraperException.StructureChanged)
        assertTrue(error!!.message!!.contains("files.other-host.example"))
    }

    @Test
    fun `javascript-only button is reported as such`() = runTest {
        site.routes["/download/neon-drift"] = {
            html("<html><body><a href=\"#\" onclick=\"go()\">Download Now</a></body></html>")
        }
        val source = analyzedSource()
        val error = runCatching { source.resolveDownload(site.baseUrl + "download/neon-drift") }.exceptionOrNull()

        assertTrue(error!!.message!!.contains("JavaScript"))
    }
}
