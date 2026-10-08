package com.rshop.scraper.testing

import com.rshop.scraper.http.HtmlFetcher
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/site/$name")) { "Missing fixture $name" }.readText()

fun html(body: String, code: Int = 200) = MockResponse.Builder()
    .code(code)
    .setHeader("Content-Type", "text/html; charset=utf-8")
    .body(body)
    .build()

/** A small binary answer, as a file server (or a redirecting download link) would send. */
fun file(name: String, type: String = "application/octet-stream") = MockResponse.Builder()
    .setHeader("Content-Type", type)
    .setHeader("Content-Disposition", "attachment; filename=\"$name\"")
    .body("7z-test-content")
    .build()

/**
 * Serves the fictional "Homebrew Hub" fixtures. Override [routes] entries per test to simulate
 * errors, and inspect [requests] to check politeness (robots, retries).
 */
class FixtureSite : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<String>()
    var robots: String = "User-agent: *\nDisallow: /private/\n"

    /** Request being answered, for routes that check headers (cookies, Referer). */
    @Volatile var current: RecordedRequest? = null
    val routes = mutableMapOf<String, () -> MockResponse>(
        "/games?page=1" to { html(fixture("games-1.html")) },
        "/games" to { html(fixture("games-1.html")) },
        "/games?page=2" to { html(fixture("games-2.html")) },
        "/game/neon-drift" to { html(fixture("game-neon-drift.html")) },
        "/game/pixel-quest" to { html(fixture("game-changed.html")) },
        "/consoles" to { html(fixture("consoles.html")) },
        "/" to { html(fixture("home-menu.html")) },
        "/news" to { html(fixture("news.html")) },
        "/console/nes/" to { html(fixture("console-nes-1.html")) },
        "/console/nes/page/2/" to { html(fixture("console-nes-2.html")) },
        "/console/gb/" to { html(fixture("console-gb-1.html")) },
        "/console/md/" to { html(fixture("console-md-1.html")) },
        "/search?type=roms&q=neon" to { html(fixture("search-neon.html")) },
        "/files/neon-drift.7z" to { file("neon-drift.7z") },
        "/files/pixel-quest.7z" to { file("pixel-quest.7z") },
    )

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                requests += target
                current = request
                if (target == "/robots.txt") return MockResponse.Builder().body(robots).build()
                return routes[target]?.invoke() ?: html("<html><body>Not found</body></html>", 404)
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString()

    fun fetcher(client: OkHttpClient = OkHttpClient()) = HtmlFetcher(
        client = client,
        userAgent = "RShop-Test/1.0",
        productToken = "RShop",
        initialBackoff = 5.milliseconds,
    )

    override fun close() = server.close()
}
