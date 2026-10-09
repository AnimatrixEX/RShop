package com.rshop.domain.model

import java.time.Instant

/** How the library orders the installed games. */
enum class LibrarySort {
    Title, RecentlyInstalled, Size, Platform;

    fun next(): LibrarySort = entries[(ordinal + 1) % entries.size]

    fun apply(games: List<InstalledGame>): List<InstalledGame> = when (this) {
        Title -> games.sortedBy { it.title.lowercase() }
        RecentlyInstalled -> games.sortedByDescending { it.installedAt }
        // Games of unknown size last.
        Size -> games.sortedWith(compareByDescending<InstalledGame> { it.sizeOnDisk ?: -1L }.thenBy { it.title.lowercase() })
        Platform -> games.sortedWith(compareBy<InstalledGame> { it.platform?.lowercase() ?: "\uffff" }.thenBy { it.title.lowercase() })
    }
}

data class InstalledGame(
    val gameId: String,
    val title: String,
    val platform: String?,
    val coverUrl: String?,
    val installedVersion: String?,
    /** Version currently published by the source; null when unknown or no longer listed. */
    val catalogVersion: String?,
    val inCatalog: Boolean,
    val sizeOnDisk: Long?,
    val installedAt: Instant,
    /** Format of the installed files ("GBA", "BIN + CUE"); null when not known yet. */
    val fileFormat: String? = null,
    /** Where the files are (stored document addresses, several separated by newlines); tells which games folder holds the game. */
    val documentUri: String = "",
) {
    /** Phase 9 replaces this plain comparison with real version ordering. */
    val updateAvailable: Boolean
        get() = inCatalog && catalogVersion != null && installedVersion != null && catalogVersion != installedVersion
}
