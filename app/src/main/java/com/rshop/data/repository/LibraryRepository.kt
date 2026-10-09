package com.rshop.data.repository

import com.rshop.data.database.dao.InstalledGameDao
import com.rshop.data.database.dao.InstalledWithCatalog
import com.rshop.domain.model.InstalledGame
import com.rshop.data.storage.GamesDirectoryManager
import com.rshop.data.storage.GamesFolder
import com.rshop.installation.DeviceSpace
import com.rshop.installation.GameInstaller
import com.rshop.installation.InstalledRomScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A usable games folder and the room left on its volume. */
data class FolderSpace(val folder: GamesFolder, val device: DeviceSpace?)

/** Installed games: what is in the user's games folders according to RShop. */
@Singleton
class LibraryRepository @Inject constructor(
    private val dao: InstalledGameDao,
    private val installer: GameInstaller,
    private val scanner: InstalledRomScanner,
    private val directoryManager: GamesDirectoryManager,
) {
    /** Adds the games found in the games folder that RShop did not install; returns how many. */
    suspend fun scanInstalled(): Int = scanner.scan()

    /** Same, unless a scan already ran recently. */
    suspend fun scanInstalledIfDue(): Int = scanner.scanIfDue()

    fun observeInstalled(): Flow<List<InstalledGame>> = dao.observeWithCatalog().map { list -> list.map { it.toDomain() } }

    /** Ids of the installed games, to mark them wherever the catalogue shows them. */
    fun observeInstalledIds(): Flow<Set<String>> = dao.observeIds().map { it.toHashSet() }

    /** Where the files of a game are (stored document addresses), null when it is not installed. */
    fun observeDocumentUri(gameId: String): Flow<String?> = dao.observeWithCatalog(gameId).map { it?.installed?.documentUri }

    fun observeInstalled(gameId: String): Flow<InstalledGame?> = dao.observeWithCatalog(gameId).map { it?.toDomain() }

    /** Deletes the files, then the entry. False when the files could not be deleted. */
    suspend fun uninstall(gameId: String): Boolean = installer.uninstall(gameId)

    /** Forgets a game whose files are already gone. */
    suspend fun forget(gameId: String) = dao.delete(gameId)

    suspend fun filesPresent(gameId: String): Boolean {
        val entity = dao.get(gameId) ?: return false
        return withContext(Dispatchers.IO) { installer.filesPresent(entity) }
    }

    /** The games folders, as they change (added, removed, access lost). */
    fun observeFolders(): Flow<List<GamesFolder>> = directoryManager.folders

    /** Free and total space of the volume of every usable games folder; null when a volume's space is unknown. */
    suspend fun folderSpaces(): List<FolderSpace> = withContext(Dispatchers.IO) {
        directoryManager.availableFolders().map { FolderSpace(it, installer.spaceOf(it)) }
    }

    /** The folder each of [games] is in, by game id. */
    suspend fun foldersOf(games: List<InstalledGame>): Map<String, GamesFolder?> =
        directoryManager.ownersOf(games.associate { it.gameId to it.documentUri })

    /**
     * The format of an installed game: stored at install time, read from the files (and stored)
     * for games installed before that. Null when the files are gone or tell nothing.
     */
    suspend fun fileFormat(gameId: String): String? {
        val entity = dao.get(gameId) ?: return null
        entity.fileFormat?.let { return it }
        val detected = withContext(Dispatchers.IO) { installer.detectFormat(entity) } ?: return null
        dao.upsert(entity.copy(fileFormat = detected))
        return detected
    }

    private fun InstalledWithCatalog.toDomain() = InstalledGame(
        gameId = installed.gameId,
        title = installed.title,
        platform = installed.platform,
        coverUrl = installed.coverUrl,
        installedVersion = installed.installedVersion,
        catalogVersion = catalogVersion,
        inCatalog = inCatalog,
        sizeOnDisk = installed.sizeOnDisk,
        installedAt = Instant.ofEpochMilli(installed.installedAt),
        fileFormat = installed.fileFormat,
        documentUri = installed.documentUri,
    )
}
