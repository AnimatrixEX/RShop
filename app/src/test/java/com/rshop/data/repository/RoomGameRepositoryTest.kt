package com.rshop.data.repository

import com.rshop.data.database.AppDatabase
import com.rshop.domain.model.CatalogFilter
import com.rshop.domain.model.SortOrder
import com.rshop.testing.TEST_NOW
import com.rshop.testing.fixedClock
import com.rshop.testing.inMemoryDatabase
import com.rshop.testing.repository
import com.rshop.testing.testGame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomGameRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomGameRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repository = db.repository()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun titles(filter: CatalogFilter) = repository.observeCatalog(filter).first().map { it.title }

    @Test
    fun `saved game round-trips through the database`() = runTest {
        val game = testGame("a", title = "Alpha", screenshots = listOf("https://x/1.png", "https://x/2.png"))
        repository.saveGames(listOf(game))

        assertEquals(game, repository.observeGame(game.id).first())
    }

    @Test
    fun `resync updates the game but keeps favorites and history`() = runTest {
        val game = testGame("a", title = "Alpha", version = "1.0")
        repository.saveGames(listOf(game))
        repository.setFavorite(game.id, true)
        repository.recordView(game.id)

        repository.saveGames(listOf(game.copy(title = "Alpha Remastered", version = "1.1")))

        assertEquals("1.1", repository.observeGame(game.id).first()?.version)
        assertTrue(repository.observeIsFavorite(game.id).first())
        assertEquals(listOf("Alpha Remastered"), repository.observeRecentlyViewed(10).first().map { it.title })
    }

    @Test
    fun `screenshots are replaced and keep their order`() = runTest {
        val game = testGame("a", screenshots = listOf("1", "2", "3"))
        repository.saveGames(listOf(game))
        repository.saveGames(listOf(game.copy(screenshots = listOf("9", "8"))))

        assertEquals(listOf("9", "8"), repository.observeGame(game.id).first()?.screenshots)
    }

    @Test
    fun `search matches word prefixes and ignores case and accents`() = runTest {
        repository.saveGames(
            listOf(
                testGame("neon", title = "Neon Drift"),
                testGame("poke", title = "Pokémon Rouge"),
                testGame("other", title = "Block Mania"),
            ),
        )

        assertEquals(listOf("Neon Drift"), titles(CatalogFilter(query = "neo")))
        assertEquals(listOf("Neon Drift"), titles(CatalogFilter(query = "DRIFT")))
        assertEquals(listOf("Pokémon Rouge"), titles(CatalogFilter(query = "pokemon")))
        assertEquals(listOf("Pokémon Rouge"), titles(CatalogFilter(query = "rou pok")))
        assertEquals(emptyList<String>(), titles(CatalogFilter(query = "zelda")))
    }

    @Test
    fun `search index follows title changes`() = runTest {
        val game = testGame("a", title = "Old Name")
        repository.saveGames(listOf(game))
        repository.saveGames(listOf(game.copy(title = "New Name")))

        assertEquals(emptyList<String>(), titles(CatalogFilter(query = "old")))
        assertEquals(listOf("New Name"), titles(CatalogFilter(query = "new")))
    }

    @Test
    fun `filters combine with search`() = runTest {
        repository.saveGames(
            listOf(
                testGame("1", title = "Quest SNES RPG", platform = "SNES", genre = "RPG"),
                testGame("2", title = "Quest GBA RPG", platform = "GBA", genre = "RPG"),
                testGame("3", title = "Quest SNES Action", platform = "SNES", genre = "Action"),
            ),
        )

        assertEquals(listOf("Quest SNES RPG"), titles(CatalogFilter(query = "quest", genre = "RPG", platform = "SNES")))
    }

    @Test
    fun `every sort order is applied`() = runTest {
        repository.saveGames(
            listOf(
                testGame("b", title = "beta", sizeBytes = 10, addedDaysAgo = 3, updatedDaysAgo = 1, popularity = 500),
                testGame("a", title = "Alpha", sizeBytes = 30, addedDaysAgo = 1, updatedDaysAgo = 3, popularity = 5),
                testGame("c", title = "Charlie", sizeBytes = 20, addedDaysAgo = 2, updatedDaysAgo = 2, popularity = 50),
            ),
        )

        assertEquals(listOf("Alpha", "beta", "Charlie"), titles(CatalogFilter(sort = SortOrder.Title)))
        assertEquals(listOf("Alpha", "Charlie", "beta"), titles(CatalogFilter(sort = SortOrder.RecentlyAdded)))
        assertEquals(listOf("beta", "Charlie", "Alpha"), titles(CatalogFilter(sort = SortOrder.RecentlyUpdated)))
        assertEquals(listOf("Alpha", "Charlie", "beta"), titles(CatalogFilter(sort = SortOrder.Size)))
        assertEquals(listOf("beta", "Charlie", "Alpha"), titles(CatalogFilter(sort = SortOrder.Popular)))
    }

    @Test
    fun `a listing without the download counter keeps the last known one`() = runTest {
        val start = TEST_NOW
        repository.saveListing(listOf(testGame("a").copy(downloadCount = 1_200, popularity = 1_200)), start)
        repository.saveListing(listOf(testGame("a")), start.plusSeconds(60))

        val game = repository.getGame("test:a")!!
        assertEquals(1_200L, game.downloadCount)
        assertEquals(1_200, game.popularity)
    }

    @Test
    fun `genres and platforms are distinct, sorted and skip blanks`() = runTest {
        repository.saveGames(
            listOf(
                testGame("1", genre = "rpg", platform = "SNES"),
                testGame("2", genre = "Action", platform = "GBA"),
                testGame("3", genre = "rpg", platform = null),
                testGame("4", genre = "", platform = "SNES"),
                testGame("5", genre = null, platform = "NES"),
            ),
        )

        // Most common first.
        assertEquals(listOf("rpg", "Action"), repository.observeGenres().first())
        assertEquals(listOf("GBA", "NES", "SNES"), repository.observePlatforms().first())
    }

    @Test
    fun `home shelves follow popularity and dates`() = runTest {
        repository.saveGames(
            listOf(
                testGame("old", popularity = 5, addedDaysAgo = 30, updatedDaysAgo = 1),
                testGame("new", popularity = 1, addedDaysAgo = 1, updatedDaysAgo = 30),
                testGame("undated", popularity = 9, addedDaysAgo = null, updatedDaysAgo = null),
            ),
        )

        assertEquals("test:undated", repository.observeFeatured().first()?.id)
        assertEquals(listOf("test:new", "test:old"), repository.observeRecentlyAdded(10).first().map { it.id })
        assertEquals(listOf("test:old", "test:new"), repository.observeRecentlyUpdated(10).first().map { it.id })
        assertEquals(listOf("test:undated"), repository.observePopular(1).first().map { it.id })
    }

    @Test
    fun `favorites toggle and list newest first`() = runTest {
        repository.saveGames(listOf(testGame("a"), testGame("b")))
        db.repository(fixedClock(TEST_NOW)).setFavorite("test:a", true)
        db.repository(fixedClock(TEST_NOW.plusSeconds(60))).setFavorite("test:b", true)

        assertEquals(listOf("test:b", "test:a"), repository.observeFavorites().first().map { it.id })

        repository.setFavorite("test:b", false)
        assertFalse(repository.observeIsFavorite("test:b").first())
        assertEquals(listOf("test:a"), repository.observeFavorites().first().map { it.id })
    }

    @Test
    fun `recently viewed is distinct and ordered by last view`() = runTest {
        repository.saveGames(listOf(testGame("a"), testGame("b")))
        db.repository(fixedClock(TEST_NOW)).recordView("test:a")
        db.repository(fixedClock(TEST_NOW.plusSeconds(10))).recordView("test:b")
        db.repository(fixedClock(TEST_NOW.plusSeconds(20))).recordView("test:a")

        assertEquals(listOf("test:a", "test:b"), repository.observeRecentlyViewed(10).first().map { it.id })
    }

    @Test
    fun `old history is pruned when recording`() = runTest {
        repository.saveGames(listOf(testGame("a"), testGame("b")))
        db.repository(fixedClock(TEST_NOW)).recordView("test:a")
        db.repository(fixedClock(TEST_NOW.plusSeconds(200L * 86_400))).recordView("test:b")

        assertEquals(listOf("test:b"), repository.observeRecentlyViewed(10).first().map { it.id })
    }

    @Test
    fun `catalog emptiness`() = runTest {
        assertTrue(repository.isCatalogEmpty())
        repository.saveGames(listOf(testGame("a")))
        assertFalse(repository.isCatalogEmpty())
        assertNull(repository.observeGame("test:missing").first())
    }

    @Test
    fun `catalogue can be limited to one source and sources are deleted separately`() = runTest {
        repository.saveGames(listOf(testGame("mine"), testGame("theirs").copy(id = "other:theirs")))

        assertEquals(listOf("mine"), titles(CatalogFilter(sourceId = "test")))
        assertEquals(listOf("mine", "theirs"), titles(CatalogFilter()))
        assertEquals(mapOf("test" to 1, "other" to 1), repository.observeCountsBySource().first())

        repository.deleteGamesFrom("other")
        assertEquals(listOf("mine"), titles(CatalogFilter()))

        repository.saveGames(listOf(testGame("demo").copy(id = "demo:demo")))
        repository.deleteGamesNotFrom(listOf("test"))
        assertEquals(listOf("mine"), titles(CatalogFilter()))
    }

    @Test
    fun `games of an unticked console go, favorites stay`() = runTest {
        repository.saveGames(
            listOf(
                testGame("a", platform = "NES"),
                testGame("b", platform = "NES"),
                testGame("c", platform = "SNES"),
                testGame("d", platform = "Game Boy").copy(id = "other:d"),
            ),
        )
        repository.setFavorite("test:b", true)

        assertEquals(1, repository.deleteGamesOfPlatforms("test", listOf("NES")))

        // "a" is gone, the favorite "b" is kept, other consoles and other sources are untouched.
        assertEquals(listOf("b", "c", "d"), titles(CatalogFilter()))
        assertEquals(0, repository.deleteGamesOfPlatforms("test", emptyList()))
    }
}
