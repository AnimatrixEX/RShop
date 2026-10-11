package com.rshop.scraper.remote

import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.FtpConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/** The real FTP and SFTP clients against real servers running in this process. */
class RemoteServersTest {

    private lateinit var home: Path
    private val closers = mutableListOf<() -> Unit>()

    private val bigFile = ByteArray(300_000) { (it * 31 % 251).toByte() }

    @Before
    fun filesOnTheServer() {
        home = Files.createTempDirectory("rshop-remote")
        home.resolve("Roms/GBA").createDirectories()
        home.resolve("Roms/GBA/Advance Wars.zip").writeBytes(bigFile)
        home.resolve("Roms/GBA/Golden Sun.7z").writeBytes(ByteArray(2_000))
        home.resolve("Roms/PlayStation/Crash Bandicoot").createDirectories()
        home.resolve("Roms/PlayStation/Crash Bandicoot/Crash Bandicoot.chd").writeBytes(ByteArray(5_000))
        closers += { home.toFile().deleteRecursively() }
    }

    @After
    fun stopServers() = closers.reversed().forEach { runCatching(it) }

    private fun startFtp(): Int {
        val users = PropertiesUserManagerFactory().createUserManager()
        users.save(BaseUser().apply {
            name = "rshop"
            password = "secret"
            homeDirectory = home.toString()
            authorities = listOf<Authority>(WritePermission())
        })
        val server: FtpServer = FtpServerFactory().apply {
            addListener("default", ListenerFactory().apply { port = 0 }.createListener())
            userManager = users
        }.createServer()
        server.start()
        closers += { server.stop() }
        return (server as org.apache.ftpserver.impl.DefaultFtpServer).listeners["default"]!!.port
    }

    private class Sftp(val port: Int, val fingerprint: String)

    private fun startSftp(): Sftp {
        val sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        val keys = SimpleGeneratorHostKeyProvider(home.resolveSibling("hostkey-${System.nanoTime()}.ser"))
        keys.algorithm = "RSA"
        keys.keySize = 2048
        sshd.keyPairProvider = keys
        sshd.passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "rshop" && password == "secret" }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())
        sshd.fileSystemFactory = VirtualFileSystemFactory(home)
        sshd.start()
        closers += { sshd.stop(true) }
        val fingerprint = KeyUtils.getFingerPrint(keys.loadKeys(null).first().public)
        return Sftp(sshd.port, fingerprint)
    }

    private fun connector(config: FtpConfig, password: String? = "secret") = RemoteConnector(config) { password }

    private fun config(protocol: RemoteProtocol, port: Int, fingerprint: String? = null, user: String = "rshop") =
        FtpConfig("srv", "Server", protocol, "localhost", port, user, "/Roms", hostKeyFingerprint = fingerprint, minRequestIntervalMs = FtpConfig.MIN_INTERVAL_MS)

    private fun source(config: FtpConfig, password: String? = "secret") = FtpSource(config, connector(config, password))

    // --- FTP -----------------------------------------------------------------------------------

    @Test
    fun `ftp - catalogue, details and file transfer with resume`() = runTest {
        val config = config(RemoteProtocol.Ftp, startFtp())
        val src = source(config)

        val inspection = src.inspect()
        assertEquals(listOf("GBA", "PlayStation"), inspection.consoles.map { it.name }.sorted())

        val games = src.crawl().toList().flatMap { it.games }
        assertEquals(listOf("Advance Wars", "Crash Bandicoot", "Golden Sun"), games.map { it.title }.sorted())

        val details = src.getGameDetails(games.first { it.title == "Advance Wars" }.id)
        val link = details.downloads.single()
        assertEquals(300_000L, link.sizeBytes)
        val info = src.resolveDownload(link.url)
        assertEquals(300_000L, info.sizeBytes)

        src.openFile(info.url, 0).use { assertArrayEquals(bigFile, it.input.readBytes()) }
        src.openFile(info.url, 123_456).use { file ->
            assertEquals(123_456L, file.startsAt)
            assertArrayEquals(bigFile.copyOfRange(123_456, bigFile.size), file.input.readBytes())
        }
    }

    @Test
    fun `ftp - a wrong password and a missing file are told apart`() = runTest {
        val port = startFtp()
        try {
            source(config(RemoteProtocol.Ftp, port), password = "wrong").inspect()
            fail()
        } catch (e: ScraperException.LoginFailed) {
            assertTrue(e.message!!.contains("localhost"))
        }
        try {
            source(config(RemoteProtocol.Ftp, port)).resolveDownload("ftp://localhost:$port/Roms/GBA/none.zip")
            fail()
        } catch (e: ScraperException.Http) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun `ftp - a closed port is a network error`() = runTest {
        val port = startFtp().also { closers.reversed().first().invoke() }
        try {
            source(config(RemoteProtocol.Ftp, port)).inspect()
            fail()
        } catch (e: ScraperException.Network) {
            // refused connection
        }
    }

    // --- SFTP ----------------------------------------------------------------------------------

    @Test
    fun `sftp - an unknown server is reported with its fingerprint, never trusted silently`() = runTest {
        val server = startSftp()
        try {
            source(config(RemoteProtocol.Sftp, server.port)).inspect()
            fail()
        } catch (e: ScraperException.HostKeyUnknown) {
            assertEquals(server.fingerprint, e.fingerprint)
        }
    }

    @Test
    fun `sftp - catalogue and file transfer with the trusted key`() = runTest {
        val server = startSftp()
        val src = source(config(RemoteProtocol.Sftp, server.port, server.fingerprint))

        val inspection = src.inspect()
        assertEquals(listOf("GBA", "PlayStation"), inspection.consoles.map { it.name }.sorted())
        val games = src.crawl().toList().flatMap { it.games }
        assertEquals(listOf("Advance Wars", "Crash Bandicoot", "Golden Sun"), games.map { it.title }.sorted())

        val link = src.getGameDetails(games.first { it.title == "Advance Wars" }.id).downloads.single()
        assertTrue(link.url.startsWith("sftp://localhost:${server.port}/"))
        val info = src.resolveDownload(link.url)
        assertEquals(300_000L, info.sizeBytes)
        src.openFile(info.url, 0).use { assertArrayEquals(bigFile, it.input.readBytes()) }
        src.openFile(info.url, 200_000).use { file ->
            assertEquals(200_000L, file.startsAt)
            assertArrayEquals(bigFile.copyOfRange(200_000, bigFile.size), file.input.readBytes())
        }
    }

    @Test
    fun `sftp - another key than the trusted one is refused`() = runTest {
        val server = startSftp()
        val other = "SHA256:" + "A".repeat(43)
        try {
            source(config(RemoteProtocol.Sftp, server.port, other)).inspect()
            fail()
        } catch (e: ScraperException.HostKeyChanged) {
            assertEquals(other, e.expected)
            assertEquals(server.fingerprint, e.actual)
        }
    }

    @Test
    fun `sftp - a wrong password is a login failure`() = runTest {
        val server = startSftp()
        try {
            source(config(RemoteProtocol.Sftp, server.port, server.fingerprint), password = "wrong").inspect()
            fail()
        } catch (e: ScraperException.LoginFailed) {
            // refused
        }
    }
}
