package com.rshop.ui.library

import com.rshop.domain.model.InstalledGame
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageUsageTest {

    private fun game(id: String, platform: String?, size: Long?) = InstalledGame(
        gameId = id, title = id, platform = platform, coverUrl = null, installedVersion = null,
        catalogVersion = null, inCatalog = true, sizeOnDisk = size, installedAt = Instant.EPOCH,
    )

    @Test
    fun `usage is summed per console, biggest first, unknown sizes skipped`() {
        val usage = storageUsage(
            listOf(game("a", "SNES", 100), game("b", "GBA", 500), game("c", "SNES", 50), game("d", "NES", null)),
            device = null,
        )
        assertEquals(650L, usage.gamesBytes)
        assertEquals(listOf("GBA", "SNES"), usage.byPlatform.map { it.platform })
        assertEquals(150L, usage.byPlatform.last().bytes)
        assertEquals(2, usage.byPlatform.last().games)
    }
}
