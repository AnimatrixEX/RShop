package com.rshop.scraper.website

import com.rshop.scraper.ScraperConfigException
import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.DetailRules
import com.rshop.scraper.config.ListRules
import com.rshop.scraper.config.ScraperConfig
import com.rshop.scraper.testing.FixtureSite
import com.rshop.scraper.testing.fixture
import com.rshop.scraper.testing.html
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteSourceTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    private fun config(
        listUrl: String = "/games?page={page}",
        nextPage: List<String> = emptyList(),
        details: DetailRules = DetailRules(
            platform = listOf("dt:matchesOwn(Platform) + dd"),
            version = listOf("li:matchesOwn(^Version) ~Version:\\s*(\\S+)"),
            size = listOf("li:matchesOwn(^Size) ~Size:\\s*(.+)"),
            description = listOf(".description", "meta[property=og:description] @content"),
            screenshots = listOf(".screenshots img @src"),
        ),
    ) = ScraperConfig(
        id = "homebrew-hub",
        name = "Homebrew Hub",
        baseUrl = site.baseUrl,
        listUrl = listUrl,
        list = ListRules(
            item = listOf("div.game-card"),
            title = listOf("h3.game-title"),
            cover = listOf("img @data-src"),
            platform = listOf(".platform"),
            genre = listOf(".genre"),
            size = listOf(".size"),
            downloadCount = listOf(".stats"),
            nextPage = nextPage,
        ),
        details = details,
        minRequestIntervalMs = 1_000,
    ).validate()

    private fun source(config: ScraperConfig = config()) = WebsiteSource(config, site.fetcher())

    @Test
    fun `list page fields are extracted`() = runTest {
        val page = source().getPage(0)

        assertEquals(4, page.games.size)
        assertTrue(page.hasNext)
        val first = page.games.first()
        assertEquals("/game/neon-drift", first.id)
        assertEquals("Neon Drift", first.title)
        assertEquals("NES", first.platform)
        assertEquals("Course", first.genre)
        assertEquals(40L * 1024, first.sizeBytes)
        assertEquals(site.baseUrl + "covers/neon-drift.png", first.coverUrl)
        assertEquals(listOf(12_345L, 1_200L, 87L, null), page.games.map { it.downloadCount })
    }

    @Test
    fun `template pagination stops at the last page`() = runTest {
        val source = source(config(nextPage = listOf("a.next @href")))
        assertTrue(source.getPage(0).hasNext)
        val second = source.getPage(1)
        assertEquals(listOf("Sky Pirates", "Farm Days"), second.games.map { it.title })
        assertFalse(second.hasNext)
    }

    @Test
    fun `next-link pagination follows the link`() = runTest {
        val source = source(config(listUrl = "/games", nextPage = listOf("a.next @href")))
        assertTrue(source.getPage(0).hasNext)
        assertEquals(2, source.getPage(1).games.size)
        assertEquals(0, source.getPage(2).games.size)
    }

    @Test
    fun `game page details and download validation`() = runTest {
        val details = source().getGameDetails("/game/neon-drift")

        assertEquals("Neon Drift", details.game.title)
        assertEquals("NES", details.game.platform)
        assertEquals("1.2", details.game.version)
        assertEquals(40L * 1024, details.game.sizeBytes)
        assertEquals(site.baseUrl + "covers/neon-drift-large.png", details.game.coverUrl)
        assertTrue(details.description!!.startsWith("Arcade racing"))
        assertEquals(2, details.screenshots.size)
        assertEquals("2026-09-14T10:00:00Z", details.updatedAt)
        // Default rules find "Téléchargements : 12 345" without any site-specific setting.
        assertEquals(12_345L, details.game.downloadCount)

        // Off-site mirror and javascript: link are rejected.
        val download = details.downloads.single()
        assertEquals(site.baseUrl + "files/neon-drift.zip", download.url)
        assertEquals("neon-drift.zip", download.fileName)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", download.sha256)
        assertEquals(40L * 1024, download.sizeBytes)
    }

    @Test
    fun `fallback rules absorb a changed layout`() = runTest {
        val details = source().getGameDetails("/game/pixel-quest")

        assertEquals("Pixel Quest", details.game.title)
        assertEquals("Top-down adventure (new layout).", details.description)
        assertEquals(site.baseUrl + "files/pixel-quest.7z", details.downloads.single().url)
        assertNull(details.downloads.single().sha256)
    }

    @Test
    fun `first page without games is a structure change`() = runTest {
        site.routes["/games?page=1"] = { html("<html><body><p>Nothing here</p></body></html>") }
        assertThrows(ScraperException.StructureChanged::class.java) { kotlinx.coroutines.runBlocking { source().getPage(0) } }
    }

    @Test
    fun `robots txt is respected`() = runTest {
        site.robots = "User-agent: *\nDisallow: /games"
        val error = runCatching { source().getPage(0) }.exceptionOrNull()
        assertTrue(error is ScraperException.BlockedByRobots)
        assertFalse(site.requests.any { it.startsWith("/games") })
    }

    @Test
    fun `access denied is reported once and never retried`() = runTest {
        site.routes["/games?page=1"] = { html("<html>Login required</html>", 403) }
        val error = runCatching { source().getPage(0) }.exceptionOrNull()
        assertTrue(error is ScraperException.AccessDenied)
        assertEquals(1, site.requests.count { it == "/games?page=1" })
    }

    @Test
    fun `server errors are retried`() = runTest {
        var calls = 0
        site.routes["/games?page=1"] = {
            calls++
            if (calls == 1) html("busy", 503) else html(fixture("games-1.html"))
        }
        assertEquals(4, source().getPage(0).games.size)
        assertEquals(2, calls)
    }

    @Test
    fun `non html responses are refused`() = runTest {
        site.routes["/games?page=1"] = {
            MockResponse.Builder().setHeader("Content-Type", "application/zip").body("PK").build()
        }
        val error = runCatching { source().getPage(0) }.exceptionOrNull()
        assertTrue(error is ScraperException.NotHtml)
    }

    @Test
    fun `game ids cannot point to another host`() = runTest {
        val error = runCatching { source().getGameDetails("//evil.example.net/game") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `invalid configs are rejected with every problem`() {
        val error = assertThrows(ScraperConfigException::class.java) {
            config().copy(id = "Bad Id", minRequestIntervalMs = 10, list = ListRules(item = listOf("div[[")))
                .validate()
        }
        assertEquals(3, error.problems.size)
    }

    @Test
    fun `config round-trips through json`() {
        val original = config()
        assertEquals(original, ScraperConfig.fromJson(original.toJson()))
    }
}
