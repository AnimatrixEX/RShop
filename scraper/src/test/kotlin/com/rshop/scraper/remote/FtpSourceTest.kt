package com.rshop.scraper.remote

import com.rshop.scraper.ScraperConfigException
import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.FtpConfig
import com.rshop.scraper.config.SourceConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FtpSourceTest {

    private fun config(root: String = "/", platform: String? = null, enabled: List<String>? = null) = FtpConfig(
        id = "ftp-test", name = "NAS", protocol = RemoteProtocol.Ftp, host = "nas.local", rootPath = root,
        platform = platform, enabledSections = enabled, minRequestIntervalMs = FtpConfig.MIN_INTERVAL_MS,
    )

    private fun source(remote: FakeRemote, config: FtpConfig = config()) = FtpSource(config, remote.connector(config))

    private fun sample() = FakeRemote().apply {
        file("/Roms/GBA/Advance Wars.zip", ByteArray(1000))
        file("/Roms/GBA/Golden Sun.7z", ByteArray(2000))
        file("/Roms/PlayStation/Final Fantasy VII/FF7 (Disc 1).chd")
        file("/Roms/PlayStation/Final Fantasy VII/FF7 (Disc 2).chd")
        file("/Docs/readme.txt")
    }

    @Test
    fun `crawl reads every console with its games over one connection`() = runTest {
        val remote = sample()
        val games = source(remote).crawl().toList().flatMap { it.games }
        assertEquals(listOf("Advance Wars", "Final Fantasy VII", "Golden Sun"), games.map { it.title }.sorted())
        assertEquals("GBA", games.first { it.title == "Golden Sun" }.platform)
        assertEquals("PlayStation", games.first { it.title == "Final Fantasy VII" }.platform)
        assertEquals(2000L, games.first { it.title == "Golden Sun" }.sizeBytes)
        // Games are identified by their path on the server.
        assertEquals("/Roms/GBA/Golden Sun.7z", games.first { it.title == "Golden Sun" }.id)
        assertEquals(1, remote.sessions)
        assertEquals(remote.sessions, remote.closed)
    }

    @Test
    fun `consoles can be chosen and a server without console folders needs one`() = runTest {
        val remote = sample()
        val all = source(remote).sections()
        assertEquals(listOf("GBA", "PlayStation"), all.map { it.name }.sorted())

        val onlyGba = config(enabled = listOf("/Roms/GBA"))
        assertEquals(listOf("Advance Wars", "Golden Sun"), source(remote, onlyGba).crawl().toList().flatMap { it.games }.map { it.title }.sorted())

        val flat = FakeRemote().apply { file("/Zelda.gba"); file("/Metroid.gba") }
        try {
            source(flat).crawl().toList()
            fail()
        } catch (e: ScraperException.StructureChanged) {
            assertTrue(e.message!!.contains("choose the console"))
        }
        val named = source(flat, config(platform = "GBA")).crawl().toList().flatMap { it.games }
        assertEquals(listOf("Metroid", "Zelda"), named.map { it.title }.sorted())
        assertTrue(named.all { it.platform == "GBA" })
    }

    @Test
    fun `inspection names the folder and lists its consoles`() = runTest {
        val inspection = source(sample(), config(root = "/Roms")).inspect()
        assertEquals("Roms", inspection.name)
        assertEquals(listOf("GBA", "PlayStation"), inspection.consoles.map { it.name }.sorted())
        try {
            source(sample(), config(root = "/missing")).inspect()
            fail()
        } catch (e: ScraperException.Http) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun `a folder that refuses us is skipped, the rest is read`() = runTest {
        val remote = sample().apply { denied += "/Roms/PlayStation" }
        val games = source(remote).crawl().toList().flatMap { it.games }
        assertEquals(listOf("Advance Wars", "Golden Sun"), games.map { it.title }.sorted())
    }

    @Test
    fun `a game folder offers its files, its updates and extras`() = runTest {
        val remote = FakeRemote().apply {
            file("/Switch/Celeste/Celeste.nsp", ByteArray(500))
            file("/Switch/Celeste/Update/Celeste [UPDATE v1.1].nsp", ByteArray(50))
            file("/Switch/Celeste/cover.jpg", ByteArray(5))
        }
        val src = source(remote)
        val game = src.crawl().toList().flatMap { it.games }.single()
        assertEquals("Celeste", game.title)
        val details = src.getGameDetails(game.id)
        val urls = details.downloads.associateBy { it.fileName }
        assertEquals(setOf("Celeste.nsp", "Celeste [UPDATE v1.1].nsp", "cover.jpg"), urls.keys)
        assertEquals("ftp://nas.local/Switch/Celeste/Celeste.nsp", urls.getValue("Celeste.nsp").url)
        assertTrue(urls.getValue("Celeste [UPDATE v1.1].nsp").isUpdate)
        assertTrue(urls.getValue("cover.jpg").isExtra)
    }

    @Test
    fun `a single file game shows the files of the same title next to it`() = runTest {
        val remote = FakeRemote().apply {
            file("/PS1/Crash.cue", ByteArray(1))
            file("/PS1/Crash.bin", ByteArray(700))
            file("/PS1/Spyro.chd", ByteArray(300))
        }
        val src = source(remote)
        val details = src.getGameDetails("/PS1/Crash.bin")
        assertEquals("/PS1/Crash.bin", details.game.id)
        assertEquals(setOf("Crash.cue", "Crash.bin"), details.downloads.mapNotNull { it.fileName }.toSet())
    }

    @Test
    fun `a game outside the source's folder is refused`() = runTest {
        val remote = sample()
        val src = source(remote, config(root = "/Roms"))
        for (id in listOf("/Docs/readme.txt", "/Roms/../Docs/readme.txt", "/RomsEvil/x.zip")) {
            try {
                src.getGameDetails(id)
                fail("$id should be refused")
            } catch (e: ScraperException.InvalidContent) {
                // refused before any connection
            }
        }
        assertEquals(0, remote.sessions)
    }

    @Test
    fun `download links stay on this server, in its folder`() = runTest {
        val src = source(sample(), config(root = "/Roms"))
        assertTrue(src.acceptsRemoteUrl(RemoteUrl(RemoteProtocol.Ftp, "NAS.local", 21, "/Roms/GBA/x.zip")))
        assertTrue(src.acceptsRemoteUrl(RemoteUrl(RemoteProtocol.Ftp, "nas.local", 21, "/Roms")))
        assertFalse(src.acceptsRemoteUrl(RemoteUrl(RemoteProtocol.Ftp, "nas.local", 21, "/Docs/readme.txt")))
        assertFalse(src.acceptsRemoteUrl(RemoteUrl(RemoteProtocol.Ftp, "nas.local", 21, "/RomsEvil/x")))
        assertFalse(src.acceptsRemoteUrl(RemoteUrl(RemoteProtocol.Sftp, "nas.local", 22, "/Roms/x")))
        assertFalse(src.acceptsRemoteUrl(RemoteUrl(RemoteProtocol.Ftp, "evil.example", 21, "/Roms/x")))
        assertFalse(src.acceptsRemoteUrl(RemoteUrl(RemoteProtocol.Ftp, "nas.local", 2121, "/Roms/x")))
        assertFalse(src.acceptsDownloadUrl("http://nas.local/Roms/x".toHttpUrl()))
    }

    @Test
    fun `resolving a download checks the file and gives its size`() = runTest {
        val src = source(sample())
        val info = src.resolveDownload("ftp://nas.local/Roms/GBA/Golden%20Sun.7z")
        assertEquals("Golden Sun.7z", info.fileName)
        assertEquals(2000L, info.sizeBytes)
        assertEquals("ftp://nas.local/Roms/GBA/Golden%20Sun.7z", info.url)
        for (bad in listOf("ftp://nas.local/Roms/GBA", "ftp://nas.local/Roms/none.zip", "ftp://other/x.zip", "https://nas.local/x.zip")) {
            try {
                src.resolveDownload(bad)
                fail("$bad should fail")
            } catch (e: ScraperException) {
                // not found, not a file, foreign host
            }
        }
    }

    @Test
    fun `opening a file uses a connection of its own, closed with the file`() = runTest {
        val remote = FakeRemote().apply { file("/a/x.zip", ByteArray(10) { it.toByte() }) }
        val src = source(remote)
        src.openFile("ftp://nas.local/a/x.zip", 4).use { file ->
            assertEquals(4L, file.startsAt)
            assertEquals(listOf<Byte>(4, 5, 6, 7, 8, 9), file.input.readBytes().toList())
        }
        assertEquals(listOf("/a/x.zip" to 4L), remote.opened)
        assertEquals(1, remote.sessions)
        assertTrue(remote.closed >= 1)
    }

    @Test
    fun `config round trips, is validated and passwords have no place in it`() {
        val original = config(root = "/Roms")
        assertEquals(original, SourceConfig.fromJson(original.toJson()))
        assertFalse(original.toJson().contains("password", ignoreCase = true))
        assertEquals("ftp://nas.local/Roms", original.location)
        assertEquals("sftp://h:2222/", FtpConfig("a", "A", RemoteProtocol.Sftp, "h", 2222).location)

        val id = FtpConfig.idFor(RemoteProtocol.Sftp, "NAS.local", 22, "me", "/Roms")
        assertTrue(SourceConfig.ID_PATTERN.matches(id))
        assertEquals(id, FtpConfig.idFor(RemoteProtocol.Sftp, "nas.LOCAL", 22, "me", "/Roms"))

        try {
            FtpConfig("Bad Id", "", RemoteProtocol.Ftp, "not a host!", 70000, "", "/a/../b", hostKeyFingerprint = "SHA256:xx").validate()
            fail()
        } catch (e: ScraperConfigException) {
            assertTrue(e.problems.size >= 6)
        }
        assertNull(config().hostKeyFingerprint)
    }
}
