package com.rshop.data.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rshop.data.database.AppDatabase
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class DemoCatalogSeederTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private fun seeder() = DemoCatalogSeeder(
        context = ApplicationProvider.getApplicationContext<Context>(),
        repository = db.repository(),
        clock = fixedClock(),
    )

    @Test
    fun `seeds the bundled demo catalogue into an empty database`() = runTest {
        seeder().seedIfEmpty()

        val games = db.repository().observePopular(100).first()
        assertTrue(games.size >= 10)
        assertTrue(games.all { it.id.startsWith("demo:") })
    }

    @Test
    fun `does nothing when the catalogue already has games`() = runTest {
        db.repository().saveGames(listOf(testGame("real")))

        seeder().seedIfEmpty()

        assertEquals(listOf("test:real"), db.repository().observePopular(100).first().map { it.id })
    }

    @Test
    fun `relative days become dates`() {
        val games = seeder().parse(
            """[{"id":"demo:x","title":"X","sizeMb":2,"addedDaysAgo":3,"updatedDaysAgo":1}]""",
            TEST_NOW,
        )
        val game = games.single()
        assertEquals(TEST_NOW - Duration.ofDays(3), game.addedAt)
        assertEquals(TEST_NOW - Duration.ofDays(1), game.updatedAt)
        assertEquals(2L * 1024 * 1024, game.sizeBytes)
    }
}
