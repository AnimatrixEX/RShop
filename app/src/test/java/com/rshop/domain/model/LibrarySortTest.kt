package com.rshop.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LibrarySortTest {

    private fun game(title: String, platform: String?, size: Long?, installed: String) = InstalledGame(
        gameId = "t:$title", title = title, platform = platform, coverUrl = null, installedVersion = null,
        catalogVersion = null, inCatalog = true, sizeOnDisk = size, installedAt = Instant.parse(installed),
    )

    private val games = listOf(
        game("Beta", "SNES", 5, "2026-01-02T00:00:00Z"),
        game("alpha", "NES", null, "2026-01-03T00:00:00Z"),
        game("Gamma", null, 90, "2026-01-01T00:00:00Z"),
        game("Delta", "NES", 40, "2026-01-04T00:00:00Z"),
    )

    private fun titles(sort: LibrarySort) = sort.apply(games).map { it.title }

    @Test
    fun `each order puts the games where a player expects`() {
        assertEquals(listOf("alpha", "Beta", "Delta", "Gamma"), titles(LibrarySort.Title))
        assertEquals(listOf("Delta", "alpha", "Beta", "Gamma"), titles(LibrarySort.RecentlyInstalled))
        // Games of unknown size come last.
        assertEquals(listOf("Gamma", "Delta", "Beta", "alpha"), titles(LibrarySort.Size))
        // By console, games without one last.
        assertEquals(listOf("alpha", "Delta", "Beta", "Gamma"), titles(LibrarySort.Platform))
    }

    @Test
    fun `cycling visits every order and comes back`() {
        assertEquals(LibrarySort.entries.toSet(), generateSequence(LibrarySort.Title) { it.next() }.take(4).toSet())
        assertEquals(LibrarySort.Title, LibrarySort.Platform.next())
    }
}
