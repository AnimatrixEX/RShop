package com.rshop.scraper.json

import com.rshop.scraper.ScraperConfigException
import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.JsonApiConfig
import com.rshop.scraper.config.SourceConfig
import com.rshop.scraper.http.JsonFetcher
import com.rshop.scraper.http.RateLimiter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class JsonApiSourceTest {

    private val server = MockWebServer()
    private val requests = mutableListOf<String>()
    private val answers = mutableMapOf<String, Pair<Int, String>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                requests += path
                val (code, body) = answers[path] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
    }

    @After
    fun close() = server.close()

    private val root get() = server.url("/api/").toString()

    private fun config(
        listUrl: String = "games?page={page}",
        searchUrl: String? = "games?q={query}",
        extra: JsonApiConfig.() -> JsonApiConfig = { this },
    ) = JsonApiConfig(
        id = "demo", name = "Demo", baseUrl = root, listUrl = listUrl, detailsUrl = "games/{id}", searchUrl = searchUrl,
        minRequestIntervalMs = 100,
    ).extra()

    private fun source(config: JsonApiConfig = config()) = JsonApiSource(
        config,
        JsonFetcher(OkHttpClient(), "RShop-Test/1.0", RateLimiter(), initialBackoff = 5.milliseconds),
    )

    private fun page(vararg ids: Int, extra: String = "") =
        """{"games": [${ids.joinToString(",") { """{"id": "g$it", "name": "Game $it", "console": "GBA"}""" }}] $extra}"""

    @Test
    fun `crawl follows pages until the API says there is no more`() = runTest {
        answers["/api/games?page=1"] = 200 to page(1, 2, extra = """, "hasNext": true""")
        answers["/api/games?page=2"] = 200 to page(3, extra = """, "hasNext": false""")
        val games = source().crawl().toList().flatMap { it.games }
        assertEquals(listOf("g1", "g2", "g3"), games.map { it.id })
        assertEquals("Game 1", games.first().title)
        assertEquals("GBA", games.first().platform)
        assertEquals(2, requests.size)
    }

    @Test
    fun `total page count ends the crawl and an empty page does too`() = runTest {
        answers["/api/games?page=1"] = 200 to page(1, extra = """, "meta": {"totalPages": 2}""")
        answers["/api/games?page=2"] = 200 to page(2, extra = """, "meta": {"totalPages": 2}""")
        assertEquals(2, source().crawl().toList().sumOf { it.games.size })
        assertEquals(2, requests.size)

        requests.clear()
        answers["/api/games?page=1"] = 200 to page(1)
        answers["/api/games?page=2"] = 200 to page()
        assertEquals(1, source().crawl().toList().sumOf { it.games.size })
        assertEquals(2, requests.size)
    }

    @Test
    fun `an API that ignores the page number does not loop forever`() = runTest {
        answers["/api/games?page=1"] = 200 to page(1, 2)
        answers["/api/games?page=2"] = 200 to page(1, 2)
        answers["/api/games?page=3"] = 200 to page(1, 2)
        val games = source().crawl().toList().flatMap { it.games }
        assertEquals(2, games.size)
        assertEquals(2, requests.size)
    }

    @Test
    fun `offset template and a single request without paging`() = runTest {
        answers["/api/games?offset=0"] = 200 to page(1, 2)
        answers["/api/games?offset=2"] = 200 to page()
        val paged = source(config(listUrl = "games?offset={offset}") { copy(pageSize = 2) })
        assertEquals(2, paged.crawl().toList().sumOf { it.games.size })
        assertEquals(listOf("/api/games?offset=0", "/api/games?offset=2"), requests)

        requests.clear()
        answers["/api/all"] = 200 to page(1, 2, 3, extra = """, "hasNext": true""")
        val single = source(config(listUrl = "all", searchUrl = null))
        assertEquals(3, single.crawl().toList().sumOf { it.games.size })
        assertEquals(listOf("/api/all"), requests)
    }

    @Test
    fun `root array, other field names and relative links`() = runTest {
        val json = """[{"slug": "mario", "title": "Mario", "cover_url": "/img/mario.png", "genres": ["platform", "action"],
            "size_bytes": 2048, "downloadCount": "12k", "version": "1.1"}, {"title": "no id"}, "junk"]"""
        answers["/api/games?page=1"] = 200 to json
        val game = source().getPage(0).games.single()
        assertEquals("mario", game.id)
        assertEquals("${server.url("/")}img/mario.png", game.coverUrl)
        assertEquals("platform, action", game.genre)
        assertEquals(2048L, game.sizeBytes)
        assertEquals(12_000L, game.downloadCount)
        assertEquals("1.1", game.version)
    }

    @Test
    fun `details give description, screenshots, files and checksums`() = runTest {
        val sha = "A".repeat(64)
        answers["/api/games/g%2F1"] = 200 to """
            {"game": {"id": "g/1", "title": "Zelda", "description": "An adventure.", "updatedAt": "2026-03-04T05:06:07Z",
              "screenshots": [{"url": "s1.png"}, {"url": "s2.png"}],
              "downloads": [
                {"url": "files/zelda.zip", "name": "zelda.zip", "size": "1.5 MB", "sha256": "$sha", "label": "ZIP"},
                {"url": "files/patch.zip", "isUpdate": true},
                {"url": "https://evil.example.net/x.zip"},
                "files/bare.7z"
              ]}}
        """.trimIndent()
        val details = source().getGameDetails("g/1")
        assertEquals("g/1", details.game.id)
        assertEquals("Zelda", details.game.title)
        assertEquals("An adventure.", details.description)
        assertEquals("2026-03-04T05:06:07Z", details.updatedAt)
        assertEquals(listOf("${root}s1.png", "${root}s2.png"), details.screenshots)
        // The foreign host is not on the allow-list, so the link is dropped.
        assertEquals(listOf("${root}files/zelda.zip", "${root}files/patch.zip", "${root}files/bare.7z"), details.downloads.map { it.url })
        val zip = details.downloads.first()
        assertEquals("zelda.zip", zip.fileName)
        assertEquals(1_572_864L, zip.sizeBytes)
        assertEquals("a".repeat(64), zip.sha256)
        assertEquals("ZIP", zip.label)
        assertTrue(details.downloads[1].isUpdate)
        assertFalse(details.downloads[2].isUpdate)
    }

    @Test
    fun `allowed download hosts and the source's own hosts`() {
        val src = source(config { copy(allowedDownloadHosts = listOf("cdn.example.org")) })
        assertTrue(src.acceptsDownloadUrl("https://cdn.example.org/x.zip".toHttpUrl()))
        assertFalse(src.acceptsDownloadUrl(server.url("/x.zip")))
        assertTrue(source().acceptsDownloadUrl(server.url("/x.zip")))
        assertFalse(source().acceptsDownloadUrl("http://elsewhere.test/x".toHttpUrl()))
    }

    @Test
    fun `search asks the API and a source without search returns nothing`() = runTest {
        answers["/api/games?q=zel%20da"] = 200 to page(7)
        assertEquals(listOf("g7"), source().search("zel da").map { it.id })
        assertTrue(source(config(searchUrl = null)).search("x").isEmpty())
    }

    @Test
    fun `errors are reported, not swallowed`() = runTest {
        answers["/api/games?page=1"] = 200 to """{"hello": "world"}"""
        try {
            source().getPage(0)
            fail()
        } catch (e: ScraperException.StructureChanged) {
            assertTrue(e.message!!.contains("list.items"))
        }
        answers["/api/games?page=1"] = 200 to "<html>not json</html>"
        try {
            source().getPage(0)
            fail()
        } catch (e: ScraperException.InvalidContent) {
            assertTrue(e.message!!.contains("not JSON"))
        }
        answers["/api/games?page=1"] = 403 to "{}"
        try {
            source().getPage(0)
            fail()
        } catch (e: ScraperException.AccessDenied) {
            assertEquals(403, e.code)
        }
        answers.remove("/api/games?page=1")
        try {
            source().getPage(0)
            fail()
        } catch (e: ScraperException.Http) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun `a base URL without trailing slash still resolves inside its folder`() = runTest {
        answers["/api/games?page=1"] = 200 to page(1)
        val src = source(config { copy(baseUrl = baseUrl.trimEnd('/')) })
        assertEquals(1, src.getPage(0).games.size)
    }

    @Test
    fun `config round trips through SourceConfig and is validated`() {
        val original = config()
        val restored = SourceConfig.fromJson(original.toJson())
        assertEquals(original, restored)
        assertNull(restored.enabledSections)

        val minimal = SourceConfig.fromJson(
            """{"type":"json","id":"a","name":"A","baseUrl":"https://example.com/api/","listUrl":"games?page={page}","detailsUrl":"games/{id}"}""",
        )
        assertTrue(minimal is JsonApiConfig)

        try {
            config(listUrl = "games?page={page}", searchUrl = "games?q=").copy(detailsUrl = "games", id = "Bad Id", list = com.rshop.scraper.config.JsonListRules(title = listOf("a..b"))).validate()
            fail()
        } catch (e: ScraperConfigException) {
            assertEquals(4, e.problems.size)
        }
    }
}
