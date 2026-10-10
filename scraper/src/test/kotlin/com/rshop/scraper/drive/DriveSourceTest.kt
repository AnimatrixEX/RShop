package com.rshop.scraper.drive

import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.DriveConfig
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

class DriveSourceTest {

    private val sample = tree("Ma collection") { root ->
        folder("Roms", root) { roms ->
            folder("GBA", roms) { gba ->
                file("Advance Wars.zip", gba, size = 1000)
                file("Golden Sun.7z", gba, size = 2000)
            }
            folder("PlayStation", roms) { ps ->
                folder("Final Fantasy VII", ps) { g ->
                    file("FF7 (Disc 1).chd", g)
                    file("FF7 (Disc 2).chd", g)
                }
            }
        }
    }

    private fun source(drive: FakeDrive, root: String, platform: String? = null, enabled: List<String>? = null, key: String? = FakeDrive.KEY) =
        DriveSource(DriveConfig(DriveConfig.idFor(root), "Drive", root, platform = platform, enabledSections = enabled), drive.api(key))

    @Test
    fun `crawl reads every console with its games`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            val pages = source(drive, root).crawl().toList()
            val games = pages.flatMap { it.games }
            assertEquals(listOf("Advance Wars", "Final Fantasy VII", "Golden Sun"), games.map { it.title }.sorted())
            assertEquals("GBA", games.first { it.title == "Golden Sun" }.platform)
            assertEquals("PlayStation", games.first { it.title == "Final Fantasy VII" }.platform)
            assertEquals(2000L, games.first { it.title == "Golden Sun" }.sizeBytes)
            // The key is sent as a parameter added by the interceptor, never by the caller.
            assertTrue(drive.requests.all { it.url.queryParameter("key") == FakeDrive.KEY })
            assertTrue(drive.requests.all { it.headers["X-Android-Package"] == "com.rshop" })
        }
    }

    @Test
    fun `listings are followed across pages`() = runTest {
        val (tree, root) = tree("GBA") { gba -> repeat(7) { file("Game $it.gba", gba) } }
        FakeDrive(tree, pageLimit = 3).use { drive ->
            val games = source(drive, root).crawl().toList().flatMap { it.games }
            assertEquals(7, games.size)
        }
    }

    @Test
    fun `a Drive without console folders uses the console chosen by the user`() = runTest {
        val (tree, root) = tree("Mes jeux") {
            folder("Wii Sports", it) { g -> file("wii-sports.rvz", g) }
            folder("Mario Kart Wii", it) { g -> file("mkw.rvz", g) }
        }
        FakeDrive(tree).use { drive ->
            try {
                source(drive, root).crawl().toList()
                fail("expected StructureChanged")
            } catch (e: ScraperException.StructureChanged) {
                assertTrue(e.message!!.contains("choose the console"))
            }
            assertTrue(source(drive, root).inspect().consoles.isEmpty())
            val games = source(drive, root, platform = "Wii").crawl().toList().flatMap { it.games }
            assertEquals(listOf("Mario Kart Wii", "Wii Sports"), games.map { it.title }.sorted())
            assertTrue(games.all { it.platform == "Wii" })
        }
    }

    @Test
    fun `only enabled consoles are read`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            val consoles = source(drive, root).inspect().consoles
            assertEquals("Ma collection", source(drive, root).inspect().name)
            val gba = consoles.first { it.name == "GBA" }.url
            val games = source(drive, root, enabled = listOf(gba)).crawl().toList().flatMap { it.games }
            assertEquals(setOf("GBA"), games.map { it.platform }.toSet())
        }
    }

    @Test
    fun `details list the files of a game folder and of a single file`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            val source = source(drive, root)
            val games = source.crawl().toList().flatMap { it.games }
            val ff7 = source.getGameDetails(games.first { it.title == "Final Fantasy VII" }.id)
            assertEquals(listOf("FF7 (Disc 1).chd", "FF7 (Disc 2).chd"), ff7.downloads.map { it.fileName })
            assertTrue(ff7.downloads.all { !it.viaPage && it.url.toHttpUrl().queryParameter("key") == null })
            val aw = source.getGameDetails(games.first { it.title == "Advance Wars" }.id)
            assertEquals("Advance Wars", aw.game.title)
            assertEquals(1, aw.downloads.size)
            assertEquals("ZIP", aw.downloads.single().label)
            assertEquals("2026-01-02T03:04:05Z", aw.updatedAt)
        }
    }

    @Test
    fun `details list the game files, the other files and the update apart`() = runTest {
        val (tree, root) = tree("Switch") {
            folder("Zelda", it) { g ->
                file("Zelda.nsp", g, size = 10)
                file("cover.jpg", g)
                folder("Updates", g) { u -> file("Zelda v1.1.nsp", u, size = 5) }
            }
        }
        FakeDrive(tree).use { drive ->
            val source = source(drive, root, platform = "Switch")
            val game = source.crawl().toList().flatMap { it.games }.single()
            assertEquals(10L, game.sizeBytes)
            val downloads = source.getGameDetails(game.id).downloads
            assertEquals(listOf("Zelda.nsp", "cover.jpg", "Zelda v1.1.nsp"), downloads.map { it.fileName })
            assertEquals(listOf(false, true, false), downloads.map { it.isExtra })
            assertEquals(listOf(false, false, true), downloads.map { it.isUpdate })
        }
    }

    @Test
    fun `a refused sub-folder is skipped, a refused shared folder is an error that names it`() = runTest {
        val (tree, root) = tree("Mes jeux") { r ->
            folder("GBA", r) { g -> file("Advance Wars.zip", g) }
            folder("Wii", r) { w -> file("Wii Sports.rvz", w) }
        }
        FakeDrive(tree).use { drive ->
            val wii = tree.files.first { it.name == "Wii" }.id
            drive.refused += wii
            val source = source(drive, root)
            val games = source.crawl().toList().flatMap { it.games }
            assertEquals(listOf("Advance Wars"), games.map { it.title })
            // Nothing may be deleted after a scan that left a folder out.
            assertTrue(source.crawlTruncated)

            drive.refused += root
            val error = runCatching { source(drive, root).crawl().toList() }.exceptionOrNull() as ScraperException.AccessDenied
            assertEquals(403, error.code)
            assertTrue(error.detail!!.contains("[folder"))
        }
    }

    @Test
    fun `listings still work when Drive leaves the parents out`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            drive.omitParents = true
            val games = source(drive, root).crawl().toList().flatMap { it.games }
            assertEquals(listOf("Advance Wars", "Final Fantasy VII", "Golden Sun"), games.map { it.title }.sorted())
        }
    }

    @Test
    fun `a big level is handed on batch by batch`() = runTest {
        val (tree, root) = tree("Mes jeux") { r ->
            repeat(85) { n -> folder("Game %02d".format(n), r) { g -> file("g$n.iso", g) } }
        }
        FakeDrive(tree).use { drive ->
            val pages = source(drive, root, platform = "PS2").crawl().toList()
            assertEquals(85, pages.sumOf { it.games.size })
            // Folders are read 40 at a time and each batch is emitted at once: three groups, not one.
            assertTrue(pages.size >= 3)
        }
    }

    @Test
    fun `a signed-in Google account reads the Drive with a bearer token and no API key`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            val config = DriveConfig(DriveConfig.idFor(root), "Drive", root)
            val source = DriveSource(config, drive.api(key = null, bearer = FakeDrive.TOKEN))
            val games = source.crawl().toList().flatMap { it.games }
            assertEquals(3, games.size)
            assertTrue(drive.requests.all { it.headers["Authorization"] == "Bearer ${FakeDrive.TOKEN}" })
            // The token never travels in the URL, and the app's key restriction headers stay out of it.
            assertTrue(drive.requests.all { it.url.queryParameter("key") == null && it.headers["X-Android-Package"] == null })
            // A wrong token is refused like a wrong key.
            expect<ScraperException.ApiKeyRejected> { DriveSource(config, drive.api(key = null, bearer = "stale")).inspect() }
        }
    }

    @Test
    fun `with a key and an account, listings use the key and downloads the account`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            // Like Drive: the account sees nothing of a folder shared by link that it never opened.
            drive.accountListsNothing = true
            // A sub-folder the link does not open makes its whole batch refused: it is read again folder by folder, with the key.
            val playStation = tree.files.first { it.name == "PlayStation" }.id
            val ff7 = tree.files.first { it.name == "Final Fantasy VII" }.id
            drive.refused += ff7
            val config = DriveConfig(DriveConfig.idFor(root), "Drive", root)
            val api = drive.api(key = FakeDrive.KEY, bearer = FakeDrive.TOKEN)
            val games = DriveSource(config, api).crawl().toList().flatMap { it.games }
            assertEquals(listOf("Advance Wars", "Golden Sun"), games.map { it.title }.sorted())
            assertTrue(drive.requests.all { it.url.queryParameter("key") == FakeDrive.KEY && it.headers["Authorization"] == null })
            assertTrue(drive.requests.any { playStation in it.url.queryParameter("q").orEmpty() })

            drive.requests.clear()
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(DriveAuthInterceptor({ DriveCredentials(FakeDrive.KEY, bearerToken = FakeDrive.TOKEN) }, appliesTo = { true }))
                .build()
            val fileId = tree.files.first { it.name == "Golden Sun.7z" }.id
            client.newCall(okhttp3.Request.Builder().url(api.mediaUrl(fileId)).build()).execute().use { assertEquals(200, it.code) }
            val media = drive.requests.single()
            assertEquals("Bearer ${FakeDrive.TOKEN}", media.headers["Authorization"])
            assertEquals(null, media.url.queryParameter("key"))
        }
    }

    @Test
    fun `a rate limit on the key is waited out, never asked again with the account`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            drive.accountListsNothing = true
            drive.failure = 403 to "userRateLimitExceeded"
            val source = DriveSource(DriveConfig(DriveConfig.idFor(root), "Drive", root), drive.api(key = FakeDrive.KEY, bearer = FakeDrive.TOKEN))
            expect<ScraperException.Busy> { source.inspect() }
            assertTrue(drive.requests.isNotEmpty())
            assertTrue(drive.requests.none { it.headers["Authorization"] != null })
        }
    }

    @Test
    fun `a download resolves to the media link with its real name`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            val source = source(drive, root)
            val id = source.crawl().toList().flatMap { it.games }.first { it.title == "Golden Sun" }.id
            val link = source.getGameDetails(id).downloads.single().url
            val info = source.resolveDownload(link)
            assertEquals("Golden Sun.7z", info.fileName)
            assertEquals(2000L, info.sizeBytes)
            assertTrue(source.acceptsDownloadUrl(info.url.toHttpUrl()))
            assertFalse(source.acceptsDownloadUrl("https://evil.example/files/x".toHttpUrl()))
        }
    }

    @Test
    fun `errors say what went wrong`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            expect<ScraperException.ApiKeyMissing> { source(drive, root, key = null).inspect() }
            expect<ScraperException.ApiKeyRejected> { source(drive, root, key = "wrong").inspect() }
            drive.failure = 403 to "downloadQuotaExceeded"
            expect<ScraperException.QuotaExceeded> { source(drive, root).inspect() }
            drive.failure = 403 to "forbidden"
            val denied = runCatching { source(drive, root).inspect() }.exceptionOrNull() as ScraperException.AccessDenied
            assertEquals(403, denied.code)
            assertTrue(denied.detail!!.contains("failure forbidden"))
            drive.failure = 403 to "dailyLimitExceededUnreg"
            expect<ScraperException.ApiKeyRejected> { source(drive, root).inspect() }
            drive.failure = 404 to "notFound"
            expect<ScraperException.Http> { source(drive, root).inspect() }
            drive.failure = 403 to "userRateLimitExceeded"
            val before = drive.requests.size
            expect<ScraperException.Busy> { source(drive, root).inspect() }
            assertEquals(4, drive.requests.size - before)
        }
    }

    @Test
    fun `resource keys travel as a header`() = runTest {
        val (tree, root) = sample
        FakeDrive(tree).use { drive ->
            val api = drive.api()
            val url = api.mediaUrl("file000001xx", "rk1")
            assertEquals("rk1", url.queryParameter(DriveAuthInterceptor.RESOURCE_KEY_PARAM))
            runCatching { api.getFile("file000001xx", "rk1") }
            assertEquals("file000001xx/rk1", drive.requests.last().headers[DriveAuthInterceptor.RESOURCE_KEYS_HEADER])
        }
    }

    @Test
    fun `links and stored configs`() {
        assertEquals(DriveLink("1AbCdEfGhIjKlMnOp", null), DriveLink.parse("https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing"))
        assertEquals(DriveLink("1AbCdEfGhIjKlMnOp", "0-xyz"), DriveLink.parse("drive.google.com/drive/u/0/folders/1AbCdEfGhIjKlMnOp?resourcekey=0-xyz"))
        assertEquals(DriveLink("1AbCdEfGhIjKlMnOp", null), DriveLink.parse("https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp"))
        assertNull(DriveLink.parse("https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view"))
        assertNull(DriveLink.parse("https://example.com/drive/folders/1AbCdEfGhIjKlMnOp"))

        val id = DriveConfig.idFor("1AbCdEfGhIjKlMnOp")
        assertTrue(SourceConfig.ID_PATTERN.matches(id))
        val config = DriveConfig(id, "Drive", "1AbCdEfGhIjKlMnOp", platform = "Wii")
        assertEquals(config, SourceConfig.fromJson(config.toJson()))
    }

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
    }
}
