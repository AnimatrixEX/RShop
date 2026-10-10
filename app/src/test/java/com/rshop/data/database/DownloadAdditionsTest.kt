package com.rshop.data.database

import com.rshop.data.database.entity.DownloadAdditionEntity
import com.rshop.domain.model.DownloadOption
import com.rshop.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadAdditionsTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private fun addition(gameId: String, vararg urls: String) =
        DownloadAdditionEntity(gameId = gameId, parts = DownloadOptionsJson.encode(urls.map { DownloadOption(url = it, isUpdate = true) })!!)

    @Test
    fun `files added to a running download are kept in order, per game, until taken`() = runTest {
        val dao = db.downloadDao()
        dao.insertAddition(addition("g1", "https://x/update.nsp"))
        dao.insertAddition(addition("g2", "https://x/other.nsp"))
        dao.insertAddition(addition("g1", "https://x/dlc1.nsp", "https://x/dlc2.nsp"))

        val taken = dao.additions("g1")
        assertEquals(
            listOf("https://x/update.nsp", "https://x/dlc1.nsp", "https://x/dlc2.nsp"),
            taken.flatMap { DownloadOptionsJson.decode(it.parts) }.map { it.url },
        )
        assertTrue(DownloadOptionsJson.decode(taken.first().parts).single().isUpdate)

        // Taking them leaves what was added afterwards, and the other games' additions.
        dao.insertAddition(addition("g1", "https://x/late.nsp"))
        dao.deleteAdditions("g1", taken.last().id)
        assertEquals(listOf("https://x/late.nsp"), dao.additions("g1").flatMap { DownloadOptionsJson.decode(it.parts) }.map { it.url })
        assertEquals(1, dao.additions("g2").size)

        // A cancelled download forgets them all.
        dao.deleteAdditions("g1")
        assertTrue(dao.additions("g1").isEmpty())
    }
}
