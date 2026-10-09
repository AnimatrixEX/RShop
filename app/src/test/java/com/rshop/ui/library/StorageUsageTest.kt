package com.rshop.ui.library

import android.net.Uri
import android.provider.DocumentsContract
import com.rshop.data.repository.FolderSpace
import com.rshop.data.storage.DirectoryLocation
import com.rshop.data.storage.GamesFolder
import com.rshop.domain.model.InstalledGame
import com.rshop.installation.DeviceSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class StorageUsageTest {

    private fun game(id: String, platform: String?, size: Long?) = InstalledGame(
        gameId = id, title = id, platform = platform, coverUrl = null, installedVersion = null,
        catalogVersion = null, inCatalog = true, sizeOnDisk = size, installedAt = Instant.EPOCH,
    )

    private fun folder(volume: String, path: String, default: Boolean = false, available: Boolean = true) = GamesFolder(
        uri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "$volume:$path"),
        location = DirectoryLocation(volume, volume == "primary", path),
        available = available,
        isDefault = default,
    )

    @Test
    fun `usage is summed per console, biggest first, unknown sizes skipped`() {
        val usage = storageUsage(
            listOf(game("a", "SNES", 100), game("b", "GBA", 500), game("c", "SNES", 50), game("d", "NES", null)),
        )
        assertEquals(650L, usage.gamesBytes)
        val only = usage.folders.single()
        assertNull(only.folder)
        assertEquals(listOf("GBA", "SNES"), only.byPlatform.map { it.platform })
        assertEquals(150L, only.byPlatform.last().bytes)
        assertEquals(2, only.byPlatform.last().games)
        assertEquals(listOf("GBA", "SNES"), usage.platformOrder)
    }

    @Test
    fun `each games folder gets its own usage and the volume around it`() {
        val internal = folder("primary", "Roms", default = true)
        val card = folder("1234-ABCD", "Games")
        val games = listOf(game("a", "SNES", 100), game("b", "GBA", 500), game("c", "SNES", 50))
        val owners = mapOf("a" to internal, "b" to card, "c" to card)
        val spaces = listOf(
            FolderSpace(internal, DeviceSpace(freeBytes = 1_000, totalBytes = 2_000)),
            FolderSpace(card, DeviceSpace(freeBytes = 5_000, totalBytes = 10_000)),
        )

        val usage = storageUsage(games, owners, spaces)

        assertEquals(650L, usage.gamesBytes)
        // The default folder comes first.
        assertEquals(listOf(internal, card), usage.folders.map { it.folder })
        assertEquals(100L, usage.folders[0].gamesBytes)
        assertEquals(550L, usage.folders[1].gamesBytes)
        assertEquals(listOf("GBA", "SNES"), usage.folders[1].byPlatform.map { it.platform })
        assertEquals(10_000L, usage.folders[1].device?.totalBytes)
    }

    @Test
    fun `an empty folder is listed with its free space, games of a removed folder are grouped apart`() {
        val internal = folder("primary", "Roms", default = true)
        val empty = folder("1234-ABCD", "Games")
        val games = listOf(game("a", "SNES", 100), game("lost", "GBA", 40))
        val owners = mapOf("a" to internal, "lost" to null)
        val spaces = listOf(FolderSpace(internal, null), FolderSpace(empty, DeviceSpace(900, 1_000)))

        val usage = storageUsage(games, owners, spaces)

        assertEquals(listOf(internal, empty, null), usage.folders.map { it.folder })
        assertEquals(0L, usage.folders[1].gamesBytes)
        assertEquals(40L, usage.folders[2].gamesBytes)
    }

    @Test
    fun `folders on the same volume share its bar`() {
        val a = folder("primary", "Roms/A", default = true)
        val b = folder("primary", "Roms/B")
        val space = DeviceSpace(freeBytes = 500, totalBytes = 1_000)
        val usage = storageUsage(
            listOf(game("x", "NES", 100), game("y", "NES", 200)),
            mapOf("x" to a, "y" to b),
            listOf(FolderSpace(a, space), FolderSpace(b, space)),
        )
        assertEquals(listOf(300L, 300L), usage.folders.map { it.volumeGamesBytes })
        assertEquals(listOf(100L, 200L), usage.folders.map { it.gamesBytes })
    }
}
