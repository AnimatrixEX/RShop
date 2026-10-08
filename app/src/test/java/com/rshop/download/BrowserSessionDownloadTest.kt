package com.rshop.download

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A file picked in the in-app browser is fetched with that browser's cookies and User-Agent. */
class BrowserSessionDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() = server.close()

    @Test
    fun `browser cookies, user agent and referer are sent with the file request`() = runTest {
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/zip").body("zip-bytes").build())
        val target = File(tmp.root, "game.zip")

        HttpFileDownloader(OkHttpClient(), "RShop-Test").download(
            url = server.url("/file/game.zip"),
            target = target,
            validators = null,
            referer = "https://example.org/game/1",
            session = BrowserSession(cookie = "session=abc; consent=1", userAgent = "Mozilla/5.0 (Linux; Android 13; wv)"),
        )

        val request = server.takeRequest()
        assertEquals("session=abc; consent=1", request.headers["Cookie"])
        assertEquals("Mozilla/5.0 (Linux; Android 13; wv)", request.headers["User-Agent"])
        assertEquals("https://example.org/game/1", request.headers["Referer"])
        assertEquals("zip-bytes", target.readText())
    }
}
