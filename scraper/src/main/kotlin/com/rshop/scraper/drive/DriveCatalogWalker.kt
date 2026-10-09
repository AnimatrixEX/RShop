package com.rshop.scraper.drive

import com.rshop.scraper.parse.ConsoleNames
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
    suspend fun scanGames(console: DriveFile, platform: String?, onGames: suspend (List<DriveGame>) -> Unit) {
        val visited = hashSetOf(console.id)
        var level = listOf(console)
        var depth = 0
        while (level.isNotEmpty()) {
            val next = mutableListOf<DriveFile>()
            // A level can hold thousands of folders: each batch is handed on as soon as it is read.
            for (chunk in level.chunked(DriveApi.BATCH)) {
            val children = list(chunk)
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
                    !GameFiles.isGroupingName(folder.name) && files.map { GameFiles.groupKey(it.name) }.distinct().size == 1
                if (isGameFolder) {
                    val others = offered.filterNot { GameFiles.isGameFile(it.name) }.sortedBy { it.name }
                    val game = DriveGame(folder.id, folder.name.trim(), platform, files.sortedBy { it.name }, others = others)
                    if (updateFolders.isEmpty()) games += game else withUpdates += game to updateFolders
                } else {
                    games += gamesOfFiles(files, platform)
                    if (depth < maxDepth) subfolders.filterTo(next) { visited.add(it.id) }
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
            game.copy(updates = folders.flatMap { updateFiles(listed[it.id].orEmpty()) })
        }
    }

    companion object {
        const val DISCOVERY_DEPTH = 3
        const val MAX_CONTAINER_FOLDERS = 40

        /** The patch files of an update folder's listing, in name order. */
        fun updateFiles(entries: List<DriveFile>): List<DriveFile> =
            entries.map { it.resolved() }.filter { !it.isFolder && !it.isGoogleDocument && GameFiles.isOffered(it.name) }.sortedBy { it.name }

        /** Loose files of one folder: one game per title. */
        fun gamesOfFiles(files: List<DriveFile>, platform: String?): List<DriveGame> =
            files.groupBy { GameFiles.groupKey(it.name) }.values.map { group ->
                val sorted = group.sortedBy { it.name }
                val title = if (group.size == 1) GameFiles.titleOf(group.single().name) else commonTitle(sorted)
                DriveGame(sorted.first().id, title, platform, sorted)
            }

        /** The title the files of one game share: "Game (Disc 1).bin" + "Game (Disc 2).bin" → "Game". */
        private fun commonTitle(files: List<DriveFile>): String = GameFiles.baseTitle(files.first().name)
    }
}
