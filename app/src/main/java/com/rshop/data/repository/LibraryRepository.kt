package com.rshop.data.repository

import com.rshop.data.database.dao.InstalledGameDao
import com.rshop.data.database.dao.InstalledWithCatalog
import com.rshop.domain.model.InstalledGame
import com.rshop.installation.GameInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Installed games: what is in the user's games folder according to RShop. */
@Singleton
class LibraryRepository @Inject constructor(
    private val dao: InstalledGameDao,
    private val installer: GameInstaller,
) {
    fun observeInstalled(): Flow<List<InstalledGame>> = dao.observeWithCatalog().map { list -> list.map { it.toDomain() } }

    fun observeInstalled(gameId: String): Flow<InstalledGame?> = dao.observeWithCatalog(gameId).map { it?.toDomain() }

    /** Deletes the files, then the entry. False when the files could not be deleted. */
    suspend fun uninstall(gameId: String): Boolean = installer.uninstall(gameId)

    /** Forgets a game whose files are already gone. */
    suspend fun forget(gameId: String) = dao.delete(gameId)

    suspend fun filesPresent(gameId: String): Boolean {
        val entity = dao.get(gameId) ?: return false
        return withContext(Dispatchers.IO) { installer.filesPresent(entity) }
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
    )
}
