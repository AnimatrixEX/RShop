package com.rshop.scraper.website

import com.rshop.scraper.ScraperException
import com.rshop.scraper.analysis.SiteAnalyzer
import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.html
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One link per format, buttons with nested labels, redirect links serving the file, CAPTCHAs. */
class DownloadOptionsTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    private fun gamePage(links: String) = html(
        """<html><head><title>Neon Drift - Homebrew Hub</title></head><body>
           <h1>Neon Drift</h1><div class="description">A racer.</div>$links</body></html>""",
    )

    private suspend fun source() = WebsiteSource(SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games").config, site.fetcher())

    @Test
    fun `every format offered by the page is kept with a label`() = runTest {
        site.routes["/game/neon-drift"] = {
            gamePage(
                """<a href="/files/neon-drift.zip">Download (ZIP)</a>
                   <a href="/files/neon-drift.7z">Download Neon Drift</a>
                   <a href="/files/neon-drift-disc2.chd">Disc 2</a>""",
            )
        }
        val details = source().getGameDetails("/game/neon-drift")

        assertEquals(listOf("ZIP", "7Z", "Disc 2 · CHD"), details.downloads.map { it.label })
    }

    @Test
    fun `button whose label sits in child spans and redirects to the file`() = runTest {
        site.routes["/game/neon-drift"] = {
            gamePage("""<div class="dl"><a class="download-btn" href="/?__df=abc123"><span>💾</span><span>Download Neon Drift</span></a></div>""")
        }
        site.routes["/?__df=abc123"] = {
            MockResponse.Builder().setHeader("Content-Type", "application/zip").body("PK\u0003\u0004").build()
        }
        val source = source()
        val download = source.getGameDetails("/game/neon-drift").downloads.single()

        assertTrue(download.viaPage)
        assertNull(download.label)
        assertEquals(site.baseUrl + "?__df=abc123", source.resolveDownload(download.url).url)
    }

    @Test
    fun `two download pages give two options`() = runTest {
        site.routes["/game/neon-drift"] = {
            gamePage(
                """<a href="/download/neon-drift?format=zip">Download ZIP</a>
                   <a href="/download/neon-drift?format=7z">Download 7z</a>""",
            )
        }
        val downloads = source().getGameDetails("/game/neon-drift").downloads

        assertEquals(2, downloads.size)
        assertTrue(downloads.all { it.viaPage })
        assertEquals(listOf("ZIP", "7z"), downloads.map { it.label })
    }

    @Test
    fun `captcha on the download page is reported, never bypassed`() = runTest {
        site.routes["/game/neon-drift"] = { gamePage("""<a href="/download/neon-drift">Download</a>""") }
        site.routes["/download/neon-drift"] = {
            html("""<html><body><form><div class="g-recaptcha" data-sitekey="k"></div><button>Get file</button></form></body></html>""")
        }
        val source = source()
        val error = runCatching { source.resolveDownload(site.baseUrl + "download/neon-drift") }.exceptionOrNull()

        assertTrue(error is ScraperException.Captcha)
    }

    @Test
    fun `menu, breadcrumb, emulator and FAQ links are not download options`() = runTest {
        site.routes["/game/neon-drift"] = {
            html(
                """<html><head><title>Neon Drift</title></head><body>
                   <header><nav><a href="/downloads/">Downloads</a> <a href="/download-emulators/">Emulators</a></nav></header>
                   <div class="breadcrumbs"><a href="/download/roms/">ROM</a> › Neon Drift</div>
                   <h1>Neon Drift</h1>
                   <article>
                     <a class="download-btn" href="/download/neon-drift">Download Neon Drift</a>
                     <a class="download-link" href="/download/pcsx2-emulator">PS2 emulator</a>
                     <a href="/download-limit-faq/">Download Limit FAQ</a>
                   </article>
                   <aside class="sidebar"><a class="download" href="/download/other-game">Other Game</a></aside>
                   </body></html>""",
            )
        }
        val downloads = source().getGameDetails("/game/neon-drift").downloads

        assertEquals(listOf(site.baseUrl + "download/neon-drift"), downloads.map { it.url })
    }

    @Test
    fun `Download ROM links are followed, even in a dropdown and with an old config`() = runTest {
        site.routes["/game/neon-drift"] = {
            gamePage(
                """<div class="dropdown"><button>Formats</button><ul class="dropdown-menu">
                     <li><a href="/dl/1">Download ROM (ZIP)</a></li>
                     <li><a href="/dl/2">Download ROM (7z)</a></li>
                   </ul></div>""",
            )
        }
        site.routes["/dl/1"] = { com.rshop.scraper.testing.file("neon-drift.zip", "application/zip") }
        val analysed = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "games").config
        // Saved before the download-button rules existed.
        val old = analysed.copy(details = analysed.details.copy(downloadPage = emptyList()))
        val source = WebsiteSource(old, site.fetcher())
        val downloads = source.getGameDetails("/game/neon-drift").downloads

        assertEquals(listOf("ROM (ZIP)", "ROM (7z)"), downloads.map { it.label })
        assertEquals("neon-drift.zip", source.resolveDownload(downloads.first().url).fileName)
    }

    @Test
    fun `table of formats and versions on the download page becomes the options`() = runTest {
        site.routes["/game/neon-drift"] = { gamePage("""<a class="btn" href="/download/neon-drift">Download ROM</a>""") }
        site.routes["/download/neon-drift"] = {
            html(
                """<html><body><h1>Neon Drift - files</h1><table class="files">
                   <tr><th>Format</th><th>Version</th><th>Size</th><th></th></tr>
                   <tr><td>ZIP</td><td>v1.1</td><td>700 MB</td><td><a href="/get/1">Download</a></td></tr>
                   <tr><td>7z</td><td>v1.0</td><td>650 MB</td><td><a href="/get/2">Download</a></td></tr>
                   </table><a href="/game/neon-drift">Back to the game</a></body></html>""",
            )
        }
        val downloads = source().getGameDetails("/game/neon-drift").downloads

        assertEquals(listOf(site.baseUrl + "get/1", site.baseUrl + "get/2"), downloads.map { it.url })
        assertEquals(listOf("ZIP · v1.1 · 700 MB", "7z · v1.0 · 650 MB"), downloads.map { it.label })
        assertEquals(700L shl 20, downloads.first().sizeBytes)
    }
}
