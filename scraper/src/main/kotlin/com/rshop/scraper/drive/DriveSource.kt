package com.rshop.scraper.drive

import com.rshop.scraper.GameSource
import com.rshop.scraper.ScraperException
import com.rshop.scraper.ScraperLog
import com.rshop.scraper.config.DriveConfig
import com.rshop.scraper.model.CatalogPage
import com.rshop.scraper.model.CatalogSection
import com.rshop.scraper.model.DownloadInfo
import com.rshop.scraper.model.ScrapedDownload
import com.rshop.scraper.model.ScrapedGame
import com.rshop.scraper.model.ScrapedGameDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicInteger

/** What a Drive folder holds, before it is added as a source. */
data class DriveInspection(
    /** Name of the shared folder. */
    val name: String,
    /** Console folders found; empty when the Drive only holds games and the user must say which console. */
    val consoles: List<CatalogSection>,
)

/**
 * A Google Drive folder as a catalogue. The Drive has no game pages: a game is only its name, its
 * files and its console; covers, screenshots and descriptions come from the artwork services.
 * Game ids are Drive ids (of the game folder or its first file).
 */
class DriveSource(
    val config: DriveConfig,
    private val api: DriveApi,
    private val log: ScraperLog = ScraperLog.None,
) : GameSource {

    override val id: String get() = config.id
    override val name: String get() = config.name

    private val root = DriveFile(id = config.folderId, name = config.name, mimeType = DriveFile.FOLDER_MIME, resourceKey = config.resourceKey)

    @Volatile
    private var truncated = false

    /** Folders refused to us during the crawl (a shortcut to someone else's folder, a restricted sub-folder). */
    private val skipped = java.util.concurrent.atomic.AtomicInteger()
    override val crawlTruncated: Boolean get() = truncated

    /** Reads the shared folder's name and its console folders. */
    suspend fun inspect(): DriveInspection {
        val folder = rootFolder()
        val consoles = walker(Budget(config.maxRequestsPerCrawl)).discoverConsoles(folder)
        return DriveInspection(folder.name, consoles.map { CatalogSection(it.name, it.folder.id) })
    }

    /** The shared folder with its real name (the source may have been renamed by the user). */
    private suspend fun rootFolder(): DriveFile {
        val folder = api.getFile(config.folderId, config.resourceKey)
        if (!folder.isFolder) throw ScraperException.InvalidContent(config.location, "the link is not a folder")
        return folder.copy(resourceKey = folder.resourceKey ?: config.resourceKey)
    }

    /**
     * The consoles: the one chosen by the user for a Drive without console folders, else the
     * console folders found. The section url is the folder id.
     */
    override suspend fun sections(): List<CatalogSection> {
        config.platform?.let { return listOf(CatalogSection(it, config.folderId)) }
        return walker(Budget(config.maxRequestsPerCrawl)).discoverConsoles(rootFolder()).map { CatalogSection(it.name, it.folder.id) }
    }

    override fun crawl(isKnown: (String) -> Boolean): Flow<CatalogPage> = flow {
        truncated = false
        val budget = Budget(config.maxRequestsPerCrawl)
        skipped.set(0)
        val walker = walker(budget)
        val consoles = if (config.platform != null) {
            listOf(DriveConsole(config.platform, root))
        } else {
            walker.discoverConsoles(rootFolder()).filter { config.enabledSections?.contains(it.folder.id) ?: true }
        }
        if (consoles.isEmpty()) {
            throw ScraperException.StructureChanged(config.location, "no console folder found: choose the console of this Drive")
        }
        try {
            for (console in consoles) {
                walker.scanGames(console.folder, console.name) { games ->
                    games.chunked(PAGE_SIZE).forEach { chunk ->
                        emit(CatalogPage(chunk.map { it.toScraped() }, hasNext = true, section = console.name))
                    }
                }
            }
        } catch (e: DriveBudgetExhausted) {
            log.warn("Drive crawl stopped after ${config.maxRequestsPerCrawl} requests (maxRequestsPerCrawl)")
            truncated = true
        }
        // Folders that could not be read were left out: the scan is not complete, nothing may be deleted from it.
        if (skipped.get() > 0) truncated = true
    }

    /** Pages only exist through [crawl]; this reads the whole tree, kept for the interface. */
    override suspend fun getPage(page: Int): CatalogPage {
        val pages = crawl().toList()
        return pages.getOrNull(page)?.copy(hasNext = page + 1 < pages.size) ?: CatalogPage(emptyList(), hasNext = false)
    }

    override suspend fun getGameDetails(id: String): ScrapedGameDetails {
        val file = api.getFile(id).resolved()
        val game = when {
            file.isFolder -> {
                val children = api.listChildren(listOf(file), full = true)[file.id].orEmpty().map { it.resolved() }
                val offered = children.filter { !it.isFolder && !it.isGoogleDocument && GameFiles.isOffered(it.name) }
                val (files, addOns) = DriveCatalogWalker.splitRoles(offered.filter { GameFiles.isGameFile(it.name) })
                val others = offered.filterNot { GameFiles.isGameFile(it.name) }.sortedBy { it.name }
                val updateFolders = children.filter { it.isFolder && GameFiles.isUpdateFolderName(it.name) }
                val updates = if (updateFolders.isEmpty()) emptyList() else {
                    val listed = api.listChildren(updateFolders, full = true)
                    updateFolders.flatMap { DriveCatalogWalker.updateFiles(listed[it.id].orEmpty()) }
                }
                DriveGame(file.id, DriveNaming.parse(file.name, isFile = false).title, config.platform, files, addOns + updates, others)
            }
            file.isGoogleDocument -> throw ScraperException.InvalidContent(id, "a Google document is not a game file")
            else -> {
                // The other files of the same title (cue + bin, discs, updates, DLC) live next to it.
                val parent = file.parents.firstOrNull()?.let { DriveFile(it, "", DriveFile.FOLDER_MIME) }
                val siblings = parent?.let { api.listChildren(listOf(it), full = true)[it.id] }.orEmpty().map { it.resolved() }
                    .filter { !it.isFolder && GameFiles.isGameFile(it.name) }
                val key = DriveNaming.parse(file.name, isFile = true).groupKey
                val group = siblings.filter { DriveNaming.parse(it.name, isFile = true).groupKey == key }.ifEmpty { listOf(file) }
                DriveCatalogWalker.gamesOfFiles(group, config.platform).first().copy(id = id)
            }
        }
        val several = game.files.size + game.others.size + game.updates.size > 1
        return ScrapedGameDetails(
            game = game.toScraped(),
            downloads = game.files.map { f ->
                ScrapedDownload(
                    url = api.mediaUrl(f.id, f.resourceKey).toString(),
                    fileName = f.name,
                    sizeBytes = f.sizeBytes,
                    label = if (several) f.name else GameFiles.extensionOf(f.name).uppercase().ifEmpty { null },
                )
            } + game.others.map { f ->
                ScrapedDownload(
                    url = api.mediaUrl(f.id, f.resourceKey).toString(),
                    fileName = f.name,
                    sizeBytes = f.sizeBytes,
                    label = f.name,
                    isExtra = true,
                )
            } + game.updates.map { f ->
                ScrapedDownload(
                    url = api.mediaUrl(f.id, f.resourceKey).toString(),
                    fileName = f.name,
                    sizeBytes = f.sizeBytes,
                    label = DriveNaming.addOnLabel(f.name),
                    isUpdate = true,
                )
            },
            updatedAt = game.modifiedAt?.toString(),
        )
    }

    /**
     * Checks the file still exists and may be read (with one metadata request) and returns its
     * download link with the real name and size.
     */
    override suspend fun resolveDownload(url: String, referer: String?): DownloadInfo {
        val parsed = url.toHttpUrlOrNull()?.takeIf(api::isApiUrl)
            ?: throw ScraperException.InvalidContent(url, "not a Google Drive file link")
        val fileId = parsed.pathSegments.getOrNull(parsed.pathSegments.indexOf("files") + 1)?.takeIf { DriveConfig.FOLDER_ID.matches(it) }
            ?: throw ScraperException.InvalidContent(url, "no file id")
        val resourceKey = parsed.queryParameter(DriveAuthInterceptor.RESOURCE_KEY_PARAM)
        val file = api.getFile(fileId, resourceKey)
        if (file.isFolder || file.isGoogleDocument) throw ScraperException.InvalidContent(url, "not a downloadable file")
        val media = api.mediaUrl(file.id, file.resourceKey ?: resourceKey).toString()
        return DownloadInfo(url = media, fileName = file.name, sizeBytes = file.sizeBytes, contentType = file.mimeType, sourcePage = referer ?: media,
            md5 = file.md5Checksum?.lowercase()?.takeIf { MD5.matches(it) },
        )
    }

    override fun acceptsDownloadUrl(url: HttpUrl): Boolean = api.isApiUrl(url)

    /** Everything is synced locally; the store's local search finds it. */
    override suspend fun search(query: String): List<ScrapedGame> = emptyList()

    private fun walker(budget: Budget) = DriveCatalogWalker(
        list = { folders ->
            repeat((folders.size + DriveApi.BATCH - 1) / DriveApi.BATCH) { if (!budget.take()) throw DriveBudgetExhausted() }
            listSkippingRefused(folders)
        },
        maxDepth = config.maxDepth,
    )

    /**
     * One folder of a Drive may refuse us (a shortcut to a folder the owner never shared, a restricted
     * sub-folder) without the rest being unreadable. The folders of a refused batch are tried one by
     * one and the refused ones are skipped; the shared folder itself must be readable.
     */
    private suspend fun listSkippingRefused(folders: List<DriveFile>): Map<String, List<DriveFile>> {
        try {
            return api.listChildren(folders)
        } catch (e: ScraperException.AccessDenied) {
            if (folders.size == 1 && folders.single().id == config.folderId) throw e
            if (folders.size == 1) return skip(folders.single(), e)
        }
        val result = LinkedHashMap<String, List<DriveFile>>()
        for (folder in folders) {
            try {
                result += api.listChildren(listOf(folder))
            } catch (e: ScraperException.AccessDenied) {
                if (folder.id == config.folderId) throw e
                result += skip(folder, e)
            }
        }
        return result
    }

    private fun skip(folder: DriveFile, cause: ScraperException.AccessDenied): Map<String, List<DriveFile>> {
        skipped.incrementAndGet()
        log.warn("Skipping folder '${folder.name}': ${cause.message}")
        return mapOf(folder.id to emptyList())
    }

    private class Budget(private val max: Int) {
        private val used = AtomicInteger()
        fun take(): Boolean = used.incrementAndGet() <= max
    }

    private fun DriveGame.toScraped() = ScrapedGame(
        id = id,
        title = title,
        platform = platform,
        sizeBytes = sizeBytes,
    )

    private companion object {
        const val PAGE_SIZE = 100
        val MD5 = Regex("[0-9a-f]{32}")
    }
}
