package com.rshop.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rshop.data.database.AppDatabase
import com.rshop.data.repository.GameListRepository
import com.rshop.scraper.config.ListRules
import com.rshop.scraper.config.ScraperConfig
import com.rshop.testing.TEST_NOW
import com.rshop.testing.fixedClock
import com.rshop.testing.inMemoryDatabase
import com.rshop.testing.repository
import com.rshop.testing.testGame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
class BackupTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "backup").deleteRecursively()
    }

    @After
    fun tearDown() = db.close()

    private val source = ScraperConfig(
        id = "demo",
        name = "Demo",
        baseUrl = "https://demo.example.org/",
        listUrl = "/games?page={page}",
        enabledSections = null,
        list = ListRules(item = listOf("div.card")),
    )

    @Test
    fun `a backup survives writing and reading`() {
        val backup = Backup(
            appVersion = "0.1.6",
            createdAt = 1_700_000_000_000,
            sources = listOf(source),
            driveSources = listOf(com.rshop.scraper.config.DriveConfig("gdrive-0123456789", "Drive", "1AbCdEfGhIjKlMnOp", platform = "Wii")),
            favorites = listOf(BackupGame("demo:/a", "Alpha", "NES")),
            lists = listOf(BackupList("To finish", listOf(BackupGame("demo:/b", "Beta")))),
            settings = BackupSettings(wifiOnly = false, themeAccent = "Orange", language = "fr"),
        )

        val read = BackupCodec.decode(BackupCodec.encode(backup))

        assertEquals(backup, read)
        assertEquals(listOf("Demo", "Drive"), read.allSources.map { it.name })
    }

    @Test
    fun `files that are not a usable backup are refused`() {
        for (text in listOf("not json", "[]", """{"format": 99}""")) {
            try {
                BackupCodec.decode(text)
                fail("accepted: $text")
            } catch (e: BackupException) {
                // expected
            }
        }
        // A source that would not pass the normal checks is refused too.
        val broken = BackupCodec.encode(Backup(sources = listOf(source.copy(id = "BAD ID"))))
        try {
            BackupCodec.decode(broken)
            fail("accepted an invalid source")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("invalid"))
        }
    }

    @Test
    fun `later fields of a newer file are ignored`() {
        val text = BackupCodec.encode(Backup(favorites = listOf(BackupGame("x:/1")))).replace("\"format\": 1", "\"format\": 1,\n  \"somethingNew\": true")
        assertEquals(1, BackupCodec.decode(text).favorites.size)
    }

    @Test
    fun `favorites wait for their game and are applied once it is in the catalogue`() = runTest {
        val games = db.repository()
        val lists = GameListRepository(db.gameListDao(), fixedClock())
        val pending = PendingRestore(context, games, lists, fixedClock())
        games.saveGames(listOf(testGame("here", title = "Here")))

        pending.add(favorites = listOf("test:here", "test:later"), listEntries = mapOf("Co-op" to listOf("test:later")))
        // The first one is already in the catalogue; the other two wait.
        assertEquals(2, pending.apply())
        assertTrue(games.observeIsFavorite("test:here").first())

        games.saveGames(listOf(testGame("later", title = "Later")))
        assertEquals(0, pending.apply())
        assertTrue(games.observeIsFavorite("test:later").first())
        val list = lists.snapshot().single()
        assertEquals("Co-op", list.name)
        assertEquals(listOf("test:later"), list.games.map { it.id })
    }

    @Test
    fun `entries that never arrive are forgotten after two weeks`() = runTest {
        val games = db.repository()
        val lists = GameListRepository(db.gameListDao(), fixedClock())
        PendingRestore(context, games, lists, fixedClock(TEST_NOW)).add(listOf("test:ghost"), emptyMap())

        val later: Clock = fixedClock(TEST_NOW.plusSeconds(15L * 24 * 3600))
        assertEquals(0, PendingRestore(context, games, lists, later).apply())
        assertTrue(!File(context.filesDir, "backup/pending.json").exists())
    }
}
