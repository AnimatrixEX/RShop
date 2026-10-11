package com.rshop.download

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

/** A redirect may lead to a mirror, but never takes the browser's cookies along nor leads into the local network. */
class RedirectTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val site = MockWebServer().apply { start() }
    private val mirror = MockWebServer().apply { start() }

    // Every name resolves to this machine, so two "different hosts" can be served by the two test servers.
    private val client = OkHttpClient.Builder().dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) }).build()
    private val downloader = HttpFileDownloader(client, "RShop-Test")

    @After
    fun tearDown() {
        site.close()
        mirror.close()
    }

    private fun download(url: String) = tmp.root.resolve("game.zip").also { target ->
        kotlinx.coroutines.runBlocking {
            downloader.download(
                url = url.toHttpUrl(),
                target = target,
                validators = null,
                session = BrowserSession(cookie = "session=secret", userAgent = "UA"),
            )
        }
    }

    @Test
    fun `the cookie stays on the site and does not follow a redirect to another host`() = runTest {
        mirror.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/zip").body("zip-bytes").build())
        site.enqueue(MockResponse.Builder().code(302).setHeader("Location", "http://mirror.test:${mirror.port}/file.zip").build())

        val file: File = download("http://origin.test:${site.port}/get")

        assertEquals("zip-bytes", file.readText())
        assertEquals("session=secret", site.takeRequest().headers["Cookie"])
        assertNull(mirror.takeRequest().headers["Cookie"])
    }

    @Test
    fun `the cookie follows a redirect inside the same host`() = runTest {
        site.enqueue(MockResponse.Builder().code(307).setHeader("Location", "/file.zip").build())
        site.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/zip").body("zip-bytes").build())

        download("http://origin.test:${site.port}/get")

        site.takeRequest()
        assertEquals("session=secret", site.takeRequest().headers["Cookie"])
    }

    @Test
    fun `a redirect into the local network is refused`() {
        site.enqueue(MockResponse.Builder().code(302).setHeader("Location", "http://192.168.1.1:${mirror.port}/admin").build())

        val error = assertThrows(DownloadException.Rejected::class.java) { download("http://origin.test:${site.port}/get") }

        assertTrue(error.message.orEmpty().contains("local network"))
        assertEquals(0, mirror.requestCount)
    }

    @Test
    fun `a redirect loop ends instead of spinning`() {
        repeat(20) { site.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/again").build()) }

        assertThrows(DownloadException.Rejected::class.java) { download("http://origin.test:${site.port}/get") }
    }

    @Test
    fun `policy refuses a downgrade to http and unknown schemes`() {
        val origin = "https://site.example/get".toHttpUrl()
        assertThrows(DownloadException.Rejected::class.java) { RedirectPolicy.check(origin, "http://mirror.example/f".toHttpUrl(), origin) }
        RedirectPolicy.check(origin, "https://mirror.example/f".toHttpUrl(), origin)
        RedirectPolicy.check("http://site.example/get".toHttpUrl(), "https://mirror.example/f".toHttpUrl(), "http://site.example/get".toHttpUrl())
    }

    @Test
    fun `local addresses are recognised without any lookup`() {
        listOf("localhost", "127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.1.1", "169.254.1.1", "[::1]".trim('[', ']'), "fd12:3456::1", "nas.local", "router.internal")
            .forEach { assertTrue(it, RedirectPolicy.isLocal(it)) }
        listOf("example.com", "8.8.8.8", "2606:4700::1111", "dl.mirror.example")
            .forEach { assertFalse(it, RedirectPolicy.isLocal(it)) }
    }
}
