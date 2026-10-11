package com.rshop.download

import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.FtpConfig
import com.rshop.scraper.remote.RemoteFile
import com.rshop.scraper.remote.RemoteFileProvider
import com.rshop.scraper.remote.RemoteProtocol
import com.rshop.scraper.remote.RemoteUrl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant

class RemoteFileDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private val url = "sftp://nas.local/Roms/GBA/Advance%20Wars.zip"

    /** A server file with a size and a date; tests change them to simulate a file replaced on the server. */
    private inner class FakeProvider : RemoteFileProvider {
        var data = content
        var modifiedAt = Instant.ofEpochSecond(1_700_000_000)
        var canResume = true
        var cutAfter: Int? = null
        val requestedOffsets = mutableListOf<Long>()
        var closed = 0
        var failure: ScraperException? = null

        override suspend fun openFile(url: String, offset: Long): RemoteFile {
            failure?.let { throw it }
            requestedOffsets += offset
            val start = if (canResume) offset.toInt() else 0
            val stream: InputStream = ByteArrayInputStream(data, start, data.size - start)
            val cut = cutAfter
            val input = if (cut == null) stream else object : InputStream() {
                private var served = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served >= cut) throw IOException("connection reset")
                    val n = stream.read(b, off, minOf(len, cut - served))
                    if (n > 0) served += n
                    return n
                }
            }
            return RemoteFile(input, data.size.toLong(), modifiedAt, start.toLong()) { closed++ }
        }
    }

    private val downloader = RemoteFileDownloader()

    @Test
    fun `a file is streamed to disk and the validator is reported`() = runTest {
        val provider = FakeProvider()
        val target = tmp.newFile("a.zip").also { it.delete() }
        var started: Pair<String, Long?>? = null
        var last = 0L
        val outcome = downloader.download(provider, url, target, validator = null, spaceMargin = 0,
            onStarted = { validator, total -> started = validator to total },
            onProgress = { done, _ -> last = done },
        )
        assertArrayEquals(content, target.readBytes())
        assertEquals(content.size.toLong(), outcome.totalBytes)
        assertEquals("300000:1700000000" to 300_000L, started)
        assertEquals(300_000L, last)
        assertEquals(listOf(0L), provider.requestedOffsets)
        assertEquals(1, provider.closed)
    }

    @Test
    fun `an interrupted transfer resumes where it stopped`() = runTest {
        val provider = FakeProvider().apply { cutAfter = 100_000 }
        val target = tmp.newFile("a.zip").also { it.delete() }
        var validator: String? = null
        try {
            downloader.download(provider, url, target, validator = null, spaceMargin = 0, onStarted = { v, _ -> validator = v })
            throw AssertionError("the cut should fail the transfer")
        } catch (e: DownloadException.Transient) {
            assertEquals(100_000L, target.length())
        }
        assertEquals("300000:1700000000", validator)

        provider.cutAfter = null
        downloader.download(provider, url, target, validator = validator, spaceMargin = 0)
        assertArrayEquals(content, target.readBytes())
        assertEquals(listOf(0L, 100_000L), provider.requestedOffsets)
    }

    @Test
    fun `a file replaced on the server is downloaded again from the start`() = runTest {
        val provider = FakeProvider().apply { cutAfter = 50_000 }
        val target = tmp.newFile("a.zip").also { it.delete() }
        var validator: String? = null
        runCatching { downloader.download(provider, url, target, null, spaceMargin = 0, onStarted = { v, _ -> validator = v }) }

        provider.cutAfter = null
        provider.modifiedAt = Instant.ofEpochSecond(1_800_000_000)
        provider.data = ByteArray(280_000) { (it % 13).toByte() }
        downloader.download(provider, url, target, validator = validator, spaceMargin = 0)

        assertArrayEquals(provider.data, target.readBytes())
        // First asked to resume (offset 50000), noticed the change, then asked again from 0.
        assertEquals(listOf(0L, 50_000L, 0L), provider.requestedOffsets)
    }

    @Test
    fun `a server that cannot resume restarts the file instead of corrupting it`() = runTest {
        val provider = FakeProvider().apply { cutAfter = 70_000 }
        val target = tmp.newFile("a.zip").also { it.delete() }
        var validator: String? = null
        runCatching { downloader.download(provider, url, target, null, spaceMargin = 0, onStarted = { v, _ -> validator = v }) }

        provider.cutAfter = null
        provider.canResume = false
        downloader.download(provider, url, target, validator = validator, spaceMargin = 0)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `not enough space is refused before anything is written`() = runTest {
        val provider = FakeProvider()
        val target = tmp.newFile("a.zip").also { it.delete() }
        try {
            downloader.download(provider, url, target, null, spaceMargin = Long.MAX_VALUE / 2)
            throw AssertionError("should not fit")
        } catch (e: DownloadException.InsufficientStorage) {
            assertFalse(target.exists() && target.length() > 0)
        }
        assertEquals(1, provider.closed)
    }

    @Test
    fun `a file bigger than the limit is refused`() = runTest {
        val provider = FakeProvider()
        val target = tmp.newFile("a.zip").also { it.delete() }
        try {
            RemoteFileDownloader(maxFileSize = 1_000).download(provider, url, target, null, spaceMargin = 0)
            throw AssertionError("too large")
        } catch (e: DownloadException.TooLarge) {
            assertEquals(300_000L, e.size)
        }
    }

    @Test
    fun `server failures keep their meaning for the user`() = runTest {
        val provider = FakeProvider()
        val target = tmp.newFile("a.zip").also { it.delete() }
        provider.failure = ScraperException.Http("sftp://x/a.zip", 404)
        assertEquals("NotFound", runCatching { downloader.download(provider, url, target, null) }.exceptionOrNull()!!.toDownloadError().kind.name)
        provider.failure = ScraperException.LoginFailed("nas.local", "Auth fail")
        assertEquals("AccessDenied", runCatching { downloader.download(provider, url, target, null) }.exceptionOrNull()!!.toDownloadError().kind.name)
        provider.failure = ScraperException.Network("sftp://x/a.zip", IOException("timeout"))
        val network = runCatching { downloader.download(provider, url, target, null) }.exceptionOrNull()!!.toDownloadError()
        assertEquals("Network", network.kind.name)
        assertTrue(network.isTransient)
    }

    @Test
    fun `links of a server are checked against the source that owns them`() {
        val config = FtpConfig("ftp-nas", "NAS", RemoteProtocol.Sftp, "nas.local", rootPath = "/Roms")
        val source = com.rshop.scraper.remote.FtpSource(config, com.rshop.scraper.remote.RemoteConnector(config) { null })

        DownloadPolicy.checkLink(url, "Advance Wars.zip", source)
        for (bad in listOf("sftp://evil.example/Roms/a.zip", "sftp://nas.local/Other/a.zip", "ftp://nas.local/Roms/a.zip")) {
            assertTrue(bad, runCatching { DownloadPolicy.checkLink(bad, "a.zip", source) }.exceptionOrNull() is DownloadException.Rejected)
        }
        assertTrue(runCatching { DownloadPolicy.checkLink("sftp://nas.local/Roms/tool.apk", "tool.apk", source) }.exceptionOrNull() is DownloadException.Rejected)
        assertTrue(runCatching { DownloadPolicy.checkLink("https://nas.local/Roms/a.zip", "a.zip", source) }.exceptionOrNull() is DownloadException.Rejected)

        assertEquals("Advance Wars.zip", DownloadPolicy.fileNameOf(url, "game"))
        assertEquals("a.zip", DownloadPolicy.fileNameOf("https://x.org/a.zip", "game"))
        assertNull(DownloadPolicy.fileNameOf("gopher://x/a", "game"))
        assertEquals(RemoteUrl.parse(url)!!.fileName, "Advance Wars.zip")
    }
}
