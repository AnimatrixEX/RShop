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
 * extraction into a hidden staging folder, then the game's own files (one file, several files such
 * as bin + cue, or the folder the archive holds) are moved straight into the console folder, next
 * to its other games. Only when a name is already taken by another game, or the storage provider
 * cannot move documents, does the game get its own `<Title>/` folder.
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
                val previousUris = previous?.documentUri?.let(::urisOf).orEmpty().toSet()
                // The second file of a game (disc 2…) joins what the first one installed.
                val append = download.partIndex > 0 && previous != null
                // Readmes and pictures next to the game are left behind; the game's own files go
                // straight into the console folder, whatever their number (bin + cue, several discs).
                val items = result.topLevelNames.filterNot { FileFormats.isExtra(it) }.ifEmpty { result.topLevelNames.toList() }
                val placed = placeInConsoleFolder(tree, platformDir, staging, items, previousUris)
                val finalUri = if (placed != null) {
                    placed.joinToString(URI_SEPARATOR.toString())
                } else if (append) {
                    // Joins the game's own folder when the first file had to be set apart.
                    placeInTitleFolder(tree, platformDir, staging, items, download.title)?.toString()
                        ?: renameStaging(tree, platformDir, staging, download.title, replace = false).also { stagingKept = true }.toString()
                } else {
                    // A name is taken by another game's file, or the provider cannot move: keep this game apart.
                    renameStaging(tree, platformDir, staging, download.title, replace = !append).also { stagingKept = true }.toString()
                }

                if (previous != null && !append) {
                    val current = urisOf(finalUri).toSet()
                    previousUris.filterNot { it in current }.forEach { storage.delete(Uri.parse(it)) }
                }
                val formats = result.formats.takeIf { it.isNotEmpty() }?.joinToString(" + ")
                InstalledGameEntity(
                    gameId = download.gameId,
                    title = download.title,
                    platform = download.platform,
                    coverUrl = download.coverUrl,
                    installedVersion = download.version,
                    documentUri = if (append) (previousUris + urisOf(finalUri)).joinToString(URI_SEPARATOR.toString()) else finalUri,
                    sizeOnDisk = if (append) (previous?.sizeOnDisk ?: 0L) + result.bytesWritten else result.bytesWritten,
                    installedAt = clock.millis(),
                    fileFormat = if (append) listOfNotNull(previous?.fileFormat, formats).joinToString(" + ").ifEmpty { null } else formats,
                ).also { installedDao.upsert(it) }
            } finally {
                if (!stagingKept) storage.delete(staging)
            }
        }

    /** Deletes the game's files and forgets it. Returns false when files could not be removed. */
    suspend fun uninstall(gameId: String): Boolean = withContext(Dispatchers.IO) {
        val installed = installedDao.get(gameId) ?: return@withContext true
        val removed = urisOf(installed.documentUri).map(Uri::parse).all { !storage.exists(it) || storage.delete(it) }
        if (removed) installedDao.delete(gameId)
        removed
    }

    /** Whether an installed game's files are still there (the user may delete them by hand). */
    fun filesPresent(installed: InstalledGameEntity): Boolean = urisOf(installed.documentUri).any { storage.exists(Uri.parse(it)) }

    /** Reads the format from the files of an install that predates the stored format. */
    suspend fun detectFormat(installed: InstalledGameEntity): String? {
        val tree = (directoryManager.state.first() as? GamesDirectoryState.Available)?.uri ?: return null
        val names = ArrayList<String>()
        fun walk(folder: Uri, depth: Int) {
            for ((childName, child) in storage.children(tree, folder)) {
                if (names.size >= MAX_SCANNED) return
                val childInfo = if (depth < MAX_DEPTH) storage.info(child) else null
                if (childInfo?.second == true) walk(child, depth + 1) else names += childName
            }
        }
        for (value in urisOf(installed.documentUri)) {
            val uri = Uri.parse(value)
            val (name, isDirectory) = storage.info(uri) ?: continue
            if (isDirectory) runCatching { walk(uri, 0) } else names += name
        }
        return FileFormats.summary(names)
    }

    /** Free and total bytes of the volume holding the games folder. */
    suspend fun deviceSpace(): DeviceSpace? {
        val tree = (directoryManager.state.first() as? GamesDirectoryState.Available)?.uri ?: return null
        return storage.volumeSpace(directoryAccess.describe(tree))
    }

    /**
     * Moves the extracted [items] from the staging folder into the console folder and returns
     * their addresses; null (nothing moved) when one name already belongs to something that is
     * not this game's previous install, or when the provider cannot move documents.
     */
    private fun placeInConsoleFolder(tree: Uri, platformDir: Uri, staging: Uri, items: List<String>, previous: Set<String>): List<String>? {
        val extracted = items.map { name ->
            name to (storage.findChild(tree, staging, name) ?: throw InstallException.Storage("Extracted item missing"))
        }
        val existing = extracted.associate { (name, _) -> name to storage.findChild(tree, platformDir, name) }
        if (existing.values.any { it != null && it.toString() !in previous }) return null
        val placed = ArrayList<String>()
        for ((name, item) in extracted) {
            existing[name]?.let { storage.delete(it) }
            val moved = storage.move(item, staging, platformDir)
            if (moved == null) {
                // The first move failing means the provider cannot move; later it is a real fault.
                if (placed.isEmpty()) return null
                placed.forEach { storage.delete(Uri.parse(it)) }
                throw InstallException.Storage("Could not move $name into the console folder")
            }
            placed += moved.toString()
        }
        return placed
    }

    private fun renameStaging(tree: Uri, platformDir: Uri, staging: Uri, title: String, replace: Boolean = true): Uri {
        val folder = SafeEntryPath.sanitizeName(title)
        if (replace) storage.findChild(tree, platformDir, folder)?.let { storage.delete(it) }
        return storage.rename(staging, folder)
    }

    /**
     * A later file of a game that was set apart in its own `<Title>/` folder joins that folder.
     * Null when there is no such folder or the provider cannot move into it.
     */
    private fun placeInTitleFolder(tree: Uri, platformDir: Uri, staging: Uri, items: List<String>, title: String): Uri? {
        val folder = storage.findChild(tree, platformDir, SafeEntryPath.sanitizeName(title)) ?: return null
        for (name in items) {
            val item = storage.findChild(tree, staging, name) ?: return null
            storage.findChild(tree, folder, name)?.let { storage.delete(it) }
            storage.move(item, staging, folder) ?: return null
        }
        return folder
    }

    private companion object {
        /** A game made of several files stores their addresses in one column, one per line. */
        const val URI_SEPARATOR = '\n'
        fun urisOf(stored: String): List<String> = stored.split(URI_SEPARATOR).filter { it.isNotEmpty() }
        const val MAX_DEPTH = 3
        const val MAX_SCANNED = 400
        const val SPACE_MARGIN = 100L * 1024 * 1024
        const val MAX_EXPANSION = 40L
        const val MIN_LIMIT = 4L * 1024 * 1024 * 1024
    }
}
