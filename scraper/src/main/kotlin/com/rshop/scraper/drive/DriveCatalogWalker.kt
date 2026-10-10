package com.rshop.scraper.drive

import com.rshop.scraper.parse.ConsoleNames
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant

/** One game found in the Drive: a file, a few files of one title (disc 1/2, cue + bin), or a game folder. */
data class DriveGame(
    /** The game folder's id, else the id of its first file. Stable across renames and moves. */
    val id: String,
    val title: String,
    val platform: String?,
    val files: List<DriveFile>,
    /** Files of the game's "Update" sub-folder, offered next to the game and downloaded after it. */
    val updates: List<DriveFile> = emptyList(),
    /** Other files of the game's folder (pictures, notes…), offered too. */
    val others: List<DriveFile> = emptyList(),
) {
    val sizeBytes: Long? get() = files.mapNotNull { it.sizeBytes }.takeIf { it.size == files.size }?.sum()
    val modifiedAt: Instant? get() = files.mapNotNull { f -> f.modifiedTime?.let { runCatching { Instant.parse(it) }.getOrNull() } }.maxOrNull()
}

data class DriveConsole(val name: String, val folder: DriveFile)

/** Raised by the walker when the request budget of a crawl is spent. */
class DriveBudgetExhausted : RuntimeException("Request budget spent")

/**
 * Turns a Drive tree into consoles and games. [list] returns the direct children of folders (one
 * call per batch, see [DriveApi.listChildren]); everything else is decided here, without network,
 * so it is tested against in-memory trees.
 *
 * Rules:
 * - a folder whose whole name is a console ("PlayStation 2", "GBA", "Mega Drive (120)") is a console;
 * - under a console, a game file is one game, except files of the same title (discs, cue + bin)
 *   which make one game together;
 * - a folder holding game files of one title and no sub-folder is one game named after the folder;
 * - other folders ("A-C", "Europe", folders of several games) only sort games and are walked through.
 */
class DriveCatalogWalker(
    private val list: suspend (List<DriveFile>) -> Map<String, List<DriveFile>>,
    private val maxDepth: Int,
) {
    /**
     * Console folders under [root], looking [DISCOVERY_DEPTH] levels down. The shared folder itself
     * is the only console when its own name is one. A folder with many sub-folders and no console
     * among them holds games, not consoles: it is not explored further.
     */
    suspend fun discoverConsoles(root: DriveFile): List<DriveConsole> {
        if (ConsoleNames.isConsoleName(root.name)) return listOf(DriveConsole(root.name.trim(), root))
        val consoles = mutableListOf<DriveConsole>()
        var level = listOf(root)
        var depth = 0
        while (level.isNotEmpty() && depth < DISCOVERY_DEPTH) {
            val children = list(level)
            val next = mutableListOf<DriveFile>()
            for (folder in level) {
                val subfolders = children[folder.id].orEmpty().map { it.resolved() }.filter { it.isFolder }
                val found = subfolders.filter { ConsoleNames.isConsoleName(it.name) }
                found.mapTo(consoles) { DriveConsole(it.name.trim(), it) }
                if (found.isEmpty() && subfolders.size > MAX_CONTAINER_FOLDERS) continue
                next += subfolders.filter { it !in found }
            }
            level = next
            depth++
        }
        return consoles.distinctBy { it.folder.id }
    }

    /**
     * Every game under [console], all of them on [platform]. [onGames] receives them level by level,
     * so a big console fills the catalogue progressively.
     */
    suspend fun scanGames(console: DriveFile, platform: String?, onGames: suspend (List<DriveGame>) -> Unit) = coroutineScope {
        val visited = hashSetOf(console.id)
        // The game folder above each folder of the walk, for an add-on folder reached on its own.
        val parentNames = HashMap<String, String>()
        var level = listOf(console)
        var depth = 0
        while (level.isNotEmpty()) {
            val next = mutableListOf<DriveFile>()
            // A level can hold thousands of folders: they are read a batch at a time, a few batches ahead
            // of the one being handled, and each batch is handed on as soon as it is read.
            val chunks = level.chunked(DriveApi.BATCH).iterator()
            val inFlight = ArrayDeque<Pair<List<DriveFile>, Deferred<Map<String, List<DriveFile>>>>>()
            fun fill() {
                while (inFlight.size < LOOKAHEAD && chunks.hasNext()) {
                    val next = chunks.next()
                    inFlight.addLast(next to async { list(next) })
                }
            }
            fill()
            while (inFlight.isNotEmpty()) {
            val (chunk, pending) = inFlight.removeFirst()
            val children = pending.await()
            fill()
            val games = mutableListOf<DriveGame>()
            val withUpdates = mutableListOf<Pair<DriveGame, List<DriveFile>>>()
            for (folder in chunk) {
                val entries = children[folder.id].orEmpty().map { it.resolved() }
                val subfolders = entries.filter { it.isFolder }
                val offered = entries.filter { !it.isFolder && !it.isGoogleDocument && GameFiles.isOffered(it.name) }
                val files = offered.filter { GameFiles.isGameFile(it.name) }
                // An "Update" sub-folder belongs to the game; any other sub-folder makes this a sorting folder.
                val updateFolders = subfolders.filter { GameFiles.isUpdateFolderName(it.name) }
                val isGameFolder = depth > 0 && subfolders.size == updateFolders.size && files.isNotEmpty() &&
                    !GameFiles.isGroupingName(folder.name) && files.map { DriveNaming.parse(it.name, isFile = true).groupKey }.distinct().size == 1
                if (isGameFolder) {
                    val others = offered.filterNot { GameFiles.isGameFile(it.name) }.sortedBy { it.name }
                    // Updates and DLC next to the game in its folder are its add-ons, not other games.
                    val (bases, addOns) = splitRoles(files)
                    val game = DriveGame(folder.id, folderTitle(folder, bases + addOns, parentNames[folder.id]), platform, bases, updates = addOns, others = others)
                    if (updateFolders.isEmpty()) games += game else withUpdates += game to updateFolders
                } else {
                    val fallback = folder.name.takeIf { depth > 0 && !GameFiles.isGroupingName(it) }
                    games += gamesOfFiles(files, platform, fallback?.let { DriveNaming.parse(it, isFile = false).title })
                    if (depth < maxDepth) {
                        val added = subfolders.filter { visited.add(it.id) }
                        next += added
                        if (depth > 0 && !GameFiles.isGroupingName(folder.name)) added.forEach { parentNames[it.id] = folder.name }
                    }
                }
            }
            if (withUpdates.isNotEmpty()) games += attachUpdates(withUpdates)
            if (games.isNotEmpty()) onGames(games)
            }
            level = next
            depth++
        }
    }

    /** Reads the update folders of this level's games in one call and attaches their files. */
    private suspend fun attachUpdates(pending: List<Pair<DriveGame, List<DriveFile>>>): List<DriveGame> {
        val listed = list(pending.flatMap { it.second }.distinctBy { it.id })
        return pending.map { (game, folders) ->
            game.copy(updates = game.updates + folders.flatMap { updateFiles(listed[it.id].orEmpty()) })
        }
    }

    companion object {
        const val DISCOVERY_DEPTH = 3

        /**
         * The game's title from its folder's name. An "Update" or "DLC" folder, or a folder named by an id
         * or a version, takes the title its files carry, else the name of the game folder above it.
         */
        fun folderTitle(folder: DriveFile, files: List<DriveFile>, parentName: String?): String {
            val own = DriveNaming.parse(folder.name, isFile = false).title
            // A folder of add-ons only ("DLC Supporters Pack", "60 FPS Patch") is named by the game its files belong to.
            val addOnsOnly = files.isNotEmpty() && files.all { DriveNaming.parse(it.name, isFile = true).role != DriveNaming.Role.Base }
            if (!addOnsOnly && !GameFiles.isUpdateFolderName(folder.name) && hasTitle(own)) return own
            files.asSequence().map { DriveNaming.parse(it.name, isFile = true).title }.firstOrNull(::hasTitle)?.let { return it }
            return parentName?.let { DriveNaming.parse(it, isFile = false).title }?.takeIf(::hasTitle) ?: own
        }

        /** A name that is a title, not an id, a version or a count ("1942" is a title, "5" left from "5 DLC" is not). */
        private fun hasTitle(title: String): Boolean = !GameFiles.isTitleless(title) && (title.any { it.isLetter() } || title.length >= 4)
        const val MAX_CONTAINER_FOLDERS = 40

        /** Batches of folders being read at the same time. */
        const val LOOKAHEAD = 4

        /** The patch files of an update folder's listing, in name order. */
        fun updateFiles(entries: List<DriveFile>): List<DriveFile> =
            entries.map { it.resolved() }.filter { !it.isFolder && !it.isGoogleDocument && GameFiles.isOffered(it.name) }.sortedBy { it.name }

        /**
         * The game files of a name-sorted list as (the game itself, its updates and DLC): `Game.nsp` is the
         * game, `Game [UPDATE].nsp` and `Game [DLC].nsp` are add-ons. A list with no game itself is all game.
         */
        fun splitRoles(files: List<DriveFile>): Pair<List<DriveFile>, List<DriveFile>> {
            val sorted = files.sortedBy { it.name }
            val (addOns, bases) = sorted.partition { DriveNaming.parse(it.name, isFile = true).role != DriveNaming.Role.Base }
            return if (bases.isEmpty()) addOns to emptyList() else bases to addOns
        }

        /**
         * Loose files of one folder: one game per title, with its updates and DLC attached. Files of the same
         * title (discs, cue + bin, one file per region) are one game offering several files.
         */
        fun gamesOfFiles(files: List<DriveFile>, platform: String?, folderTitle: String? = null): List<DriveGame> {
            val groups = LinkedHashMap<String, MutableList<DriveFile>>()
            val parsed = files.associateWith { DriveNaming.parse(it.name, isFile = true) }
            for (file in files) groups.getOrPut(parsed.getValue(file).groupKey) { mutableListOf() } += file

            // "Celeste (Update 1.1).nsp" carries no id: it joins the game of the same title that has one.
            val idOfTitle = groups.filterKeys { it.startsWith(DriveNaming.ID_PREFIX) }.mapNotNull { (key, members) ->
                members.firstOrNull { parsed.getValue(it).role == DriveNaming.Role.Base }
                    ?.let { DriveNaming.titleKey(parsed.getValue(it).title) to key }
            }.toMap()
            for (key in groups.keys.toList()) {
                if (key.startsWith(DriveNaming.ID_PREFIX)) continue
                val members = groups.getValue(key)
                if (members.any { parsed.getValue(it).role == DriveNaming.Role.Base }) continue
                val target = idOfTitle[key] ?: continue
                groups.getValue(target) += members
                groups.remove(key)
            }

            return groups.values.map { group ->
                val (bases, addOns) = splitRoles(group)
                // A file named by its id alone ("0100AF800C950000.nsp") takes its folder's title.
                val title = parsed.getValue(bases.first()).title.takeIf(::hasTitle) ?: folderTitle?.takeIf(::hasTitle) ?: parsed.getValue(bases.first()).title
                DriveGame(bases.first().id, title, platform, bases, updates = addOns)
            }
        }
    }
}
