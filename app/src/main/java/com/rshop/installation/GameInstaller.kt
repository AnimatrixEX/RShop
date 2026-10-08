package com.rshop.installation

import android.net.Uri
import com.rshop.data.database.dao.InstalledGameDao
import com.rshop.data.database.entity.DownloadEntity
import com.rshop.data.database.entity.InstalledGameEntity
import com.rshop.data.storage.GamesDirectoryAccess
import com.rshop.data.storage.GamesDirectoryManager
import com.rshop.data.storage.GamesDirectoryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Arcade sets must stay zipped: MAME/FBNeo load the .zip itself. */
object InstallPolicy {
    private val keepZipped = Regex("(?i)arcade|mame|fbneo|fba|final ?burn|neo ?geo|cps ?[123]|naomi|atomiswave")

    fun keepArchive(platform: String?): Boolean = platform != null && keepZipped.containsMatchIn(platform)

    /** Folder for a console inside the games folder. */
    fun platformFolder(platform: String?): String = SafeEntryPath.sanitizeName(platform ?: "Other", "Other")
}

/**
 * Installs a verified download into `<games folder>/<Console>/`:
 *
 * extraction into a hidden staging folder → if it produced a single file or folder, that item is
 * moved next to the console's other games, otherwise the staging folder becomes `<Title>/`.
 * A failure removes the staging folder and leaves previous installs untouched.
 */
@Singleton
class GameInstaller @Inject constructor(
    private val storage: SafGameStorage,
    private val directoryManager: GamesDirectoryManager,
    private val directoryAccess: GamesDirectoryAccess,
    private val installedDao: InstalledGameDao,
    private val clock: Clock,
) {

    suspend fun install(download: DownloadEntity, file: File, onProgress: (Long) -> Unit): InstalledGameEntity =
        withContext(Dispatchers.IO) {
            val tree = when (val state = directoryManager.state.first()) {
                is GamesDirectoryState.Available -> state.uri
                GamesDirectoryState.AccessLost -> throw InstallException.DirectoryLost()
                GamesDirectoryState.NotSelected -> throw InstallException.NoGamesDirectory()
            }
            val root = storage.rootDocument(tree)
            val platformDir = storage.findOrCreateDirectory(tree, root, InstallPolicy.platformFolder(download.platform))

            val stagingName = ".rshop-${Integer.toHexString(download.gameId.hashCode())}.tmp"
            storage.findChild(tree, platformDir, stagingName)?.let { storage.delete(it) }
            val staging = storage.findOrCreateDirectory(tree, platformDir, stagingName)
            // Set when the staging folder itself became the game folder (it must then be kept).
            var stagingKept = false

            try {
                var format = ArchiveExtractor.detect(file)
                if (format == ArchiveFormat.Zip && InstallPolicy.keepArchive(download.platform)) format = ArchiveFormat.Raw
                val available = storage.availableBytes(directoryAccess.describe(tree))
                val limits = ExtractionLimits(
                    maxTotalBytes = minOf(
                        available?.minus(SPACE_MARGIN)?.coerceAtLeast(0) ?: Long.MAX_VALUE,
                        maxOf(file.length() * MAX_EXPANSION, MIN_LIMIT),
                    ),
                )
                Timber.i("Installing %s (%s, %d bytes)", download.title, format, file.length())
                val result = ArchiveExtractor.extract(file, format, download.fileName, SafSink(storage, tree, staging), limits, onProgress)

                val previous = installedDao.get(download.gameId)
                val finalUri = if (result.topLevelNames.size == 1) {
                    val name = result.topLevelNames.single()
                    val item = storage.findChild(tree, staging, name) ?: throw InstallException.Storage("Extracted item missing")
                    val existing = storage.findChild(tree, platformDir, name)
                    if (existing != null && existing.toString() != previous?.documentUri) {
                        // Same name as another game's file: never overwrite it, use a game folder.
                        renameStaging(tree, platformDir, staging, download.title).also { stagingKept = true }
                    } else {
                        existing?.let { storage.delete(it) }
                        storage.move(item, staging, platformDir) ?: run {
                            // Provider cannot move: keep the game in its own folder instead.
                            renameStaging(tree, platformDir, staging, download.title).also { stagingKept = true }
                        }
                    }
                } else {
                    renameStaging(tree, platformDir, staging, download.title).also { stagingKept = true }
                }

                if (previous != null && previous.documentUri != finalUri.toString()) {
                    storage.delete(Uri.parse(previous.documentUri))
                }
                InstalledGameEntity(
                    gameId = download.gameId,
                    title = download.title,
                    platform = download.platform,
                    coverUrl = download.coverUrl,
                    installedVersion = download.version,
                    documentUri = finalUri.toString(),
                    sizeOnDisk = result.bytesWritten,
                    installedAt = clock.millis(),
                ).also { installedDao.upsert(it) }
            } finally {
                if (!stagingKept) storage.delete(staging)
            }
        }

    /** Deletes the game's files and forgets it. Returns false when files could not be removed. */
    suspend fun uninstall(gameId: String): Boolean = withContext(Dispatchers.IO) {
        val installed = installedDao.get(gameId) ?: return@withContext true
        val uri = Uri.parse(installed.documentUri)
        val removed = !storage.exists(uri) || storage.delete(uri)
        if (removed) installedDao.delete(gameId)
        removed
    }

    /** Whether an installed game's files are still there (the user may delete them by hand). */
    fun filesPresent(installed: InstalledGameEntity): Boolean = storage.exists(Uri.parse(installed.documentUri))

    private fun renameStaging(tree: Uri, platformDir: Uri, staging: Uri, title: String): Uri {
        val folder = SafeEntryPath.sanitizeName(title)
        storage.findChild(tree, platformDir, folder)?.let { storage.delete(it) }
        return storage.rename(staging, folder)
    }

    private companion object {
        const val SPACE_MARGIN = 100L * 1024 * 1024
        const val MAX_EXPANSION = 40L
        const val MIN_LIMIT = 4L * 1024 * 1024 * 1024
    }
}
