package com.rshop.scraper.website

import com.rshop.scraper.analysis.PaginationKind
import com.rshop.scraper.analysis.SiteAnalyzer
import com.rshop.scraper.parse.ConsoleNames
import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.file
import com.rshop.scraper.testing.html
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexAndButtonsTest {

    private fun letterPage(letter: String, games: List<String>, extra: String = "") = html(
        """
        <html><head><title>Games $letter - Homebrew Hub</title></head><body>
        <header><nav><a href="/">Home</a><a href="/about">About</a></nav></header>
        <div id="az"><a href="/list/">#</a> <a href="/list/A">A</a> <a href="/list/B">B</a> <a href="/list/C">C</a> <a href="/list/D">D</a></div>
        <div class="grid">
        ${games.joinToString("\n") { "<div class=\"game-card\"><a href=\"/game/$it\"><img src=\"/img/$it.png\"><h3>${it.replace('-', ' ')}</h3></a><span>NES</span></div>" }}
        <div class="game-card"><a href="/console/gamecube"><img src="/img/gc.png"><h3>Nintendo GameCube</h3></a><span>Console</span></div>
        </div>$extra
        </body></html>
        """.trimIndent(),
    )

    private val site = FixtureSite().apply {
        routes["/list/"] = { letterPage("#", listOf("1942-tribute", "3d-maze", "8-bit-hero")) }
        routes["/list/A"] = { letterPage("A", listOf("alpha-run", "astro-bug", "aqua-dive")) }
        routes["/list/B"] = { letterPage("B", listOf("blob-bros", "box-pusher")) }
        routes["/list/C"] = { letterPage("C", emptyList()) }
        // D is paginated with plain numbered links.
        routes["/list/D"] = {
            letterPage("D", listOf("dune-dash", "dot-eater"), "<div class=\"pager\"><a href=\"/list/D\">1</a> <a href=\"/list/D-p2\">2</a></div>")
        }
        routes["/list/D-p2"] = { letterPage("D", listOf("dragon-den"), "<div class=\"pager\"><a href=\"/list/D\">1</a> <a href=\"/list/D-p2\">2</a></div>") }
        routes["/game/alpha-run"] = {
            html(
                """
                <html><head><title>Alpha Run</title></head><body><h1>Alpha Run</h1>
                <button class="btn" onclick="window.location.href='/get/alpha-run'">Download</button>
                </body></html>
                """.trimIndent(),
            )
        }
        routes["/game/astro-bug"] = {
            html("<html><body><h1>Astro Bug</h1><button data-href=\"/get/astro-bug\"><i></i> Télécharger</button></body></html>")
        }
        // Buttons known only by their class: the label says nothing ("Get ROM", an icon…).
        routes["/game/by-data"] = {
            html("""<html><body><h1>By Data</h1><button class="download-btn" data-url="/get/by-data"><i></i></button></body></html>""")
        }
        routes["/game/bypass"] = {
            html("""<html><body><h1>Bypass</h1><div class="actions"><a href="/get/bypass"><button class="bypass-download-btn">Get ROM</button></a></div></body></html>""")
        }
        routes["/game/by-form"] = {
            html("""<html><body><h1>By Form</h1><form action="/get/by-form"><button type="submit" class="btn btn-download">Obtenir</button></form></body></html>""")
        }
        routes["/game/by-click"] = {
            html("""<html><body><h1>By Click</h1><div class="download-btn" onclick="location.href='/get/by-click'">Get ROM</div></body></html>""")
        }
        routes["/game/by-link"] = {
            html("""<html><body><h1>By Link</h1><a class="btn dl-button" href="/get/by-link">Get ROM</a></body></html>""")
        }
        // A post form and a download counter are not download buttons.
        routes["/game/not-a-button"] = {
            html("""<html><body><h1>No Button</h1><span class="downloads-count">1 234</span><form action="/search"><button class="search-btn">Go</button></form></body></html>""")
        }
        listOf("by-data", "bypass", "by-form", "by-click", "by-link").forEach { routes["/get/$it"] = { file("$it.zip") } }
        routes["/get/alpha-run"] = { file("alpha-run.zip") }
        routes["/get/astro-bug"] = { file("astro-bug.zip") }
    }

    @After
    fun tearDown() = site.close()

    @Test
    fun `whole console labels are recognised, game titles are not`() {
        listOf(
            "Nintendo GameCube", "Game Boy Advance ROMs", "PS2 (4 512)", "Sega Mega Drive", "Nintendo DS", "Xbox", "Atari 2600",
            "Switch", "Nintendo Switch NSP", "Switch NSP / XCI", "Nintendo Switch Roms (NSP & XCI)", "Switch - 1234 games", "Switch Lite",
        ).forEach {
            assertTrue(it, ConsoleNames.isConsoleName(it))
        }
        listOf("Super Mario Bros", "Mario Kart DS", "Sonic the Hedgehog", "NES Remix", "Alpha Run").forEach {
            assertFalse(it, ConsoleNames.isConsoleName(it))
        }
    }

    @Test
    fun `analysis detects the letter index and crawl reads every index page without console cards`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "list/A")
        assertEquals(PaginationKind.Index, analysis.pagination)
        assertTrue(analysis.config.list.indexPages.isNotEmpty())
        assertTrue(analysis.sampleGames.none { it.title.contains("GameCube") })

        val pages = WebsiteSource(analysis.config, site.fetcher()).crawl().toList()
        val ids = pages.flatMap { it.games }.map { it.id }
        assertEquals(
            setOf("alpha-run", "astro-bug", "aqua-dive", "1942-tribute", "3d-maze", "8-bit-hero", "blob-bros", "box-pusher", "dune-dash", "dot-eater", "dragon-den")
                .map { "/game/$it" }.toSet(),
            ids.toSet(),
        )
        assertEquals(ids.size, ids.distinct().size)
        // Each page read once, empty letter included.
        assertEquals(1, site.requests.count { it == "/list/C" })
        assertEquals(1, site.requests.count { it == "/list/D-p2" })
    }

    @Test
    fun `download buttons with onclick navigation or data-href are followed`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "list/A")
        val source = WebsiteSource(analysis.config, site.fetcher())

        val alpha = source.getGameDetails("/game/alpha-run").downloads.single()
        assertEquals(site.baseUrl + "get/alpha-run", alpha.url)
        assertEquals(site.baseUrl + "get/alpha-run", source.resolveDownload(alpha.url).url)

        val astro = source.getGameDetails("/game/astro-bug").downloads.single()
        assertEquals(site.baseUrl + "get/astro-bug", astro.url)
    }

    @Test
    fun `buttons recognised by their class are followed, whatever their label`() = runTest {
        val analysis = SiteAnalyzer(site.fetcher()).analyze(site.baseUrl + "list/A")
        val source = WebsiteSource(analysis.config, site.fetcher())

        listOf("by-data", "bypass", "by-form", "by-click", "by-link").forEach { name ->
            assertEquals(name, site.baseUrl + "get/$name", source.getGameDetails("/game/$name").downloads.single().url)
        }
        assertTrue(source.getGameDetails("/game/not-a-button").downloads.isEmpty())
    }
}
