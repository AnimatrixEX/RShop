package com.rshop.installation

import android.net.Uri
import com.rshop.data.database.dao.GameDao
import com.rshop.data.database.dao.InstalledGameDao
import com.rshop.data.database.dao.MatchCandidate
import com.rshop.data.database.entity.InstalledGameEntity
import com.rshop.data.storage.GamesDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds games that are already in the games folder without having been installed by RShop and
 * adds them to the library, so the catalogue shows them as installed. A file or folder inside
 * `<games folder>/<Console>/` belongs to a catalogue game when their cleaned-up names are equal
 * (see [RomNames]); the files of one game (bin + cue, several discs) are grouped.
 */
@Singleton
class InstalledRomScanner @Inject constructor(
    private val storage: SafGameStorage,
    private val directoryManager: GamesDirectoryManager,
    private val gameDao: GameDao,
    private val installedDao: InstalledGameDao,
    private val clock: Clock,
) {
    private val lock = Mutex()
    @Volatile private var lastScanAt = 0L

    /** Scans unless a scan already ran recently in this session. */
    suspend fun scanIfDue(): Int = if (clock.millis() - lastScanAt < MIN_INTERVAL_MS) 0 else scan()

    /** Number of games newly added to the library. */
    suspend fun scan(): Int = lock.withLock {
        val found = withContext(Dispatchers.IO) {
            // Every games folder the user added is looked through.
            directoryManager.availableFolders().sumOf { folder ->
                try {
                    detect(folder.uri)
                } catch (e: InstallException) {
                    Timber.w(e, "Scan of the games folder failed")
                    0
                } catch (e: SecurityException) {
                    Timber.w(e, "Scan of the games folder refused")
                    0
                }
            }
        }
        lastScanAt = clock.millis()
        found
    }

    private suspend fun detect(tree: Uri): Int {
        val consoles = storage.entries(tree, storage.rootDocument(tree)).filter { it.isDirectory && !it.name.startsWith(".") }
        if (consoles.isEmpty()) return 0

        val installed = installedDao.getAll()
        val taken = installed.mapTo(HashSet()) { it.gameId }
        val known = installed.flatMapTo(HashSet()) { it.documentUri.split(URI_SEPARATOR) }

        val catalogue = gameDao.catalogueForMatching().filter { RomNames.key(it.title).isNotEmpty() }
        val inFolder = catalogue.groupBy { InstallPolicy.platformFolder(it.platform).lowercase() to RomNames.key(it.title) }
        val byTitle = catalogue.groupBy { RomNames.key(it.title) }
        val consoleFolders = catalogue.mapTo(HashSet()) { InstallPolicy.platformFolder(it.platform).lowercase() }

        var found = 0
        for (console in consoles) {
            val folder = console.name.lowercase()
            val matches = LinkedHashMap<MatchCandidate, MutableList<StoredEntry>>()
            for (entry in storage.entries(tree, console.uri)) {
                if (entry.name.startsWith(".") || entry.uri.toString() in known) continue
                if (!entry.isDirectory && FileFormats.isExtra(entry.name)) continue
                val key = RomNames.key(if (entry.isDirectory) entry.name else RomNames.withoutExtension(entry.name))
                if (key.isEmpty()) continue
                val game = if (folder in consoleFolders) {
                    inFolder[folder to key]?.firstOrNull { it.id !in taken }
                } else {
                    // A folder named in the user's own way: only an unambiguous title is trusted.
                    byTitle[key]?.singleOrNull()?.takeIf { it.id !in taken }
                } ?: continue
                matches.getOrPut(game) { ArrayList() } += entry
            }
            for ((game, entries) in matches) {
                installedDao.upsert(
                    InstalledGameEntity(
                        gameId = game.id,
                        title = game.title,
                        platform = game.platform,
                        coverUrl = game.coverUrl,
                        installedVersion = game.version,
                        documentUri = entries.joinToString(URI_SEPARATOR.toString()) { it.uri.toString() },
                        sizeOnDisk = entries.sumOf { sizeOf(tree, it) },
                        installedAt = entries.mapNotNull { it.modifiedAt }.maxOrNull() ?: clock.millis(),
                        fileFormat = FileFormats.summary(entries.filterNot { it.isDirectory }.map { it.name }),
                    ),
                )
                taken += game.id
                found++
            }
        }
        Timber.i("Games folder scan: %d games detected", found)
        return found
    }

    private fun sizeOf(tree: Uri, entry: StoredEntry): Long {
        if (!entry.isDirectory) return entry.size ?: 0L
        var total = 0L
        var visited = 0
        fun walk(folder: Uri) {
            for (child in storage.entries(tree, folder)) {
                if (++visited > MAX_VISITED) return
                if (child.isDirectory) walk(child.uri) else total += child.size ?: 0L
            }
        }
        runCatching { walk(entry.uri) }
        return total
    }

    private companion object {
        const val URI_SEPARATOR = '\n'
        const val MIN_INTERVAL_MS = 30 * 60_000L
        const val MAX_VISITED = 5_000
    }
}
