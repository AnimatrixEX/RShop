package com.rshop.download

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class HttpFileDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Serves [content] with ETag and Range support; tests swap behaviours through flags. */
    private var supportsRange = true
    private var etag = "\"v1\""
    private var cutAfter: Int? = null
    private var contentType = "application/zip"
    private var refusal: String? = null

    private val downloader = HttpFileDownloader(OkHttpClient(), "RShop-Test")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                refusal?.let { return MockResponse.Builder().code(403).setHeader("Content-Type", "application/json").body(it).build() }
                val range = request.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                val ifRange = request.headers["If-Range"]
                val builder = MockResponse.Builder().setHeader("Content-Type", contentType).setHeader("ETag", etag)
                if (range != null && supportsRange && ifRange == etag) {
                    val slice = content.copyOfRange(range, content.size)
                    return builder.code(206)
                        .setHeader("Content-Range", "bytes $range-${content.size - 1}/${content.size}")
                        .body(okio.Buffer().write(slice))
                        .build()
                }
                val cut = cutAfter
                if (cut != null) {
                    cutAfter = null
                    return builder.code(200)
                        .setHeader("Content-Length", content.size)
                        .body(okio.Buffer().write(content.copyOf(cut)))
                        .onResponseBody(SocketEffect.ShutdownConnection)
                        .build()
                }
                return builder.code(200).body(okio.Buffer().write(content)).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private val url get() = server.url("/files/game.zip")
    private fun target() = File(tmp.root, "dl/game.zip")

    @Test
    fun `referer and session cookies are sent like a browser click`() = runTest {
        val jar = com.rshop.scraper.http.MemoryCookieJar()
        jar.saveFromResponse(url, listOf(okhttp3.Cookie.parse(url, "dlsession=abc; Path=/")!!))
        val withCookies = HttpFileDownloader(OkHttpClient.Builder().cookieJar(jar).build(), "RShop-Test")

        withCookies.download(url, target(), null, referer = "https://example.com/download/game")

        val request = requests.last()
        assertEquals("https://example.com/download/game", request.headers["Referer"])
        assertEquals("dlsession=abc", request.headers["Cookie"])
    }

    @Test
    fun `full download streams to disk`() = runTest {
        val outcome = downloader.download(url, target(), null)
        assertEquals(content.size.toLong(), outcome.totalBytes)
        assertArrayEquals(content, target().readBytes())
        assertEquals("\"v1\"", outcome.validators.etag)
    }

    @Test
    fun `interrupted download resumes with a range request`() = runTest {
        cutAfter = 100_000
        val first = runCatching { downloader.download(url, target(), null) }
        assertTrue(first.exceptionOrNull() is DownloadException.Transient)
        val partial = target().length()
        assertTrue(partial in 1..<content.size)

        downloader.download(url, target(), ResumeValidators(etag, null))

        assertArrayEquals(content, target().readBytes())
        assertEquals("bytes=$partial-", requests.last().headers["Range"])
    }

    @Test
    fun `changed file on the server restarts from zero`() = runTest {
        target().parentFile!!.mkdirs()
        target().writeBytes(content.copyOf(1000))
        etag = "\"v2\""

        downloader.download(url, target(), ResumeValidators("\"v1\"", null))

        // If-Range did not match, so the server sent the whole file and it replaced the old bytes.
        assertArrayEquals(content, target().readBytes())
    }

    @Test
    fun `server without range support restarts cleanly`() = runTest {
        supportsRange = false
        target().parentFile!!.mkdirs()
        target().writeBytes(content.copyOf(5000))

        downloader.download(url, target(), ResumeValidators(etag, null))
        assertArrayEquals(content, target().readBytes())
    }

    @Test
    fun `html answer is refused`() = runTest {
        contentType = "text/html; charset=utf-8"
        val error = runCatching { downloader.download(url, target(), null) }.exceptionOrNull()
        assertTrue(error is DownloadException.UnexpectedHtml)
    }

    @Test
    fun `access denied and missing files are not transient`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(
                if (request.url.encodedPath.endsWith("denied")) 403 else 404,
            ).build()
        }
        assertTrue(runCatching { downloader.download(server.url("/denied"), target(), null) }.exceptionOrNull() is DownloadException.AccessDenied)
        assertTrue(runCatching { downloader.download(server.url("/missing"), target(), null) }.exceptionOrNull() is DownloadException.NotFound)
    }

    @Test
    fun `size limit is enforced`() = runTest {
        val small = HttpFileDownloader(OkHttpClient(), "RShop-Test", maxFileSize = 1000)
        assertTrue(runCatching { small.download(url, target(), null) }.exceptionOrNull() is DownloadException.TooLarge)
    }

    @Test
    fun `sha256 verification`() = runTest {
        downloader.download(url, target(), null)
        val md5 = IntegrityVerifier.md5(target())
        IntegrityVerifier.verifyMd5(target(), md5.uppercase())
        assertTrue(runCatching { IntegrityVerifier.verifyMd5(target(), "0".repeat(32)) }.exceptionOrNull() is DownloadException.ChecksumMismatch)
        val good = IntegrityVerifier.sha256(target())
        IntegrityVerifier.verify(target(), good.uppercase())
        val error = runCatching { IntegrityVerifier.verify(target(), "0".repeat(64)) }.exceptionOrNull()
        assertTrue(error is DownloadException.ChecksumMismatch)
    }

    @Test
    fun `executables and foreign hosts are rejected before downloading`() {
        val sameHost: (okhttp3.HttpUrl) -> Boolean = { it.host == "roms.example" }
        DownloadPolicy.check("https://roms.example/a.zip".toHttpUrl(), "a.zip", sameHost)
        assertTrue(runCatching { DownloadPolicy.check("https://roms.example/a.apk".toHttpUrl(), "a.apk", sameHost) }.exceptionOrNull() is DownloadException.Rejected)
        assertTrue(runCatching { DownloadPolicy.check("https://evil.example/a.zip".toHttpUrl(), "a.zip", sameHost) }.exceptionOrNull() is DownloadException.Rejected)
        assertEquals("Neon Drift.zip", DownloadPolicy.fileName("https://x.org/files/Neon%20Drift.zip".toHttpUrl(), "game"))
        assertNull(null)
    }

    @Test
    fun `a spent Drive quota is told apart from a refusal`() = runTest {
        refusal = """{"error":{"errors":[{"reason":"downloadQuotaExceeded"}],"code":403}}"""
        assertTrue(runCatching { downloader.download(url, target(), null) }.exceptionOrNull() is DownloadException.QuotaExceeded)
        refusal = """{"error":{"errors":[{"reason":"forbidden"}],"code":403}}"""
        assertTrue(runCatching { downloader.download(url, target(), null) }.exceptionOrNull() is DownloadException.AccessDenied)
    }
}
