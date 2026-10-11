package com.rshop.scraper.remote

import com.rshop.scraper.GameSource
import com.rshop.scraper.ScraperException
import com.rshop.scraper.ScraperLog
import com.rshop.scraper.config.FtpConfig
import com.rshop.scraper.drive.DriveBudgetExhausted
import com.rshop.scraper.drive.DriveCatalogWalker
import com.rshop.scraper.drive.DriveConsole
import com.rshop.scraper.drive.DriveFile
import com.rshop.scraper.drive.DriveGame
import com.rshop.scraper.drive.DriveNaming
import com.rshop.scraper.drive.GameFiles
import com.rshop.scraper.model.CatalogPage
import com.rshop.scraper.model.CatalogSection
import com.rshop.scraper.model.DownloadInfo
import com.rshop.scraper.model.ScrapedDownload
import com.rshop.scraper.model.ScrapedGame
import com.rshop.scraper.model.ScrapedGameDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicInteger

/** What a server holds, before it is added as a source. */
data class FtpInspection(
    /** A name to give the source (the folder, else the host). */
    val name: String,
    /** Console folders found; empty when the server only holds games and the user must say which console. */
    val consoles: List<CatalogSection>,
)

/**
 * A folder of an FTP, FTPS or SFTP server as a catalogue. Like a Drive, the server has no game pages:
 * a game is its name, its files and its console, and covers, screenshots and descriptions come from
 * the artwork services. The tree is read with the same rules as a Drive ([DriveCatalogWalker]); ids and
 * section urls are server paths. Files are streamed by [openFile], one connection per file.
 */
class FtpSource(
    val config: FtpConfig,
    private val connector: RemoteConnector,
    private val log: ScraperLog = ScraperLog.None,
) : GameSource, RemoteFileProvider {

    override val id: String get() = config.id
    override val name: String get() = config.name

    private val rootUrl = config.rootUrl
    private val rootPath = rootUrl.path
    private val root = DriveFile(id = rootPath, name = rootName(), mimeType = DriveFile.FOLDER_MIME)

    @Volatile
    private var truncated = false
    private val skipped = AtomicInteger()
    override val crawlTruncated: Boolean get() = truncated

    /** Connects and reads the console folders; fails with the reason when the server cannot be used. */
    suspend fun inspect(): FtpInspection = withSession { session ->
        val folder = io { session.stat(rootPath) } ?: throw ScraperException.Http(rootUrl.toString(), 404)
        if (!folder.isDirectory) throw ScraperException.InvalidContent(rootUrl.toString(), "the path is not a folder")
        val consoles = walker(session, Budget(config.maxRequestsPerCrawl)).discoverConsoles(root)
        FtpInspection(rootName(), consoles.map { CatalogSection(it.name, it.folder.id) })
    }

    override suspend fun sections(): List<CatalogSection> {
        config.platform?.let { return listOf(CatalogSection(it, rootPath)) }
        return withSession { session ->
            walker(session, Budget(config.maxRequestsPerCrawl)).discoverConsoles(root).map { CatalogSection(it.name, it.folder.id) }
        }
    }

    override fun crawl(isKnown: (String) -> Boolean): Flow<CatalogPage> = flow {
        truncated = false
        skipped.set(0)
        val session = connector.connect()
        try {
            val walker = walker(session, Budget(config.maxRequestsPerCrawl))
            val consoles = if (config.platform != null) {
                listOf(DriveConsole(config.platform, root))
            } else {
                walker.discoverConsoles(root).filter { config.enabledSections?.contains(it.folder.id) ?: true }
            }
            if (consoles.isEmpty()) {
                throw ScraperException.StructureChanged(config.location, "no console folder found: choose the console of this server")
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
                log.warn("Server crawl stopped after ${config.maxRequestsPerCrawl} listings (maxRequestsPerCrawl)")
                truncated = true
            }
            if (skipped.get() > 0) log.warn("Server crawl left out ${skipped.get()} folder(s) that cannot be read")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { session.close() }
        }
    }

    /** Pages only exist through [crawl]; this reads the whole tree, kept for the interface. */
    override suspend fun getPage(page: Int): CatalogPage {
        val pages = crawl().toList()
        return pages.getOrNull(page)?.copy(hasNext = page + 1 < pages.size) ?: CatalogPage(emptyList(), hasNext = false)
    }

    override suspend fun getGameDetails(id: String): ScrapedGameDetails {
        // Refused before any connection is made.
        val path = checkedPath(id)
        return withSession { session -> io { gameDetails(session, path) } }
    }

    private fun gameDetails(session: RemoteSession, path: String): ScrapedGameDetails {
        val entry = session.stat(path) ?: throw ScraperException.Http(remoteUrl(path), 404)
        val file = entry.toFile(parentOf(path))
        val game = if (file.isFolder) {
            val children = session.list(path).map { it.toFile(path) }
            val offered = children.filter { !it.isFolder && GameFiles.isOffered(it.name) }
            val (files, addOns) = DriveCatalogWalker.splitRoles(offered.filter { GameFiles.isGameFile(it.name) })
            val others = offered.filterNot { GameFiles.isGameFile(it.name) }.sortedBy { it.name }
            val updates = children.filter { it.isFolder && GameFiles.isUpdateFolderName(it.name) }
                .flatMap { folder -> DriveCatalogWalker.updateFiles(readable(session, folder)) }
            DriveGame(file.id, DriveCatalogWalker.folderTitle(file, files + addOns, null), config.platform, files, addOns + updates, others)
        } else {
            // The other files of the same title (cue + bin, discs, updates, DLC) live next to it.
            val parent = parentOf(path)
            val siblings = session.list(parent).map { it.toFile(parent) }.filter { !it.isFolder && GameFiles.isGameFile(it.name) }
            val key = DriveNaming.parse(file.name, isFile = true).groupKey
            val group = siblings.filter { DriveNaming.parse(it.name, isFile = true).groupKey == key }.ifEmpty { listOf(file) }
            DriveCatalogWalker.gamesOfFiles(group, config.platform).first().copy(id = path)
        }
        val several = game.files.size + game.others.size + game.updates.size > 1
        return ScrapedGameDetails(
            game = game.toScraped(),
            downloads = game.files.map { f ->
                ScrapedDownload(
                    url = remoteUrl(f.id),
                    fileName = f.name,
                    sizeBytes = f.sizeBytes,
                    label = if (several) f.name else GameFiles.extensionOf(f.name).uppercase().ifEmpty { null },
                )
            } + game.others.map { f ->
                ScrapedDownload(url = remoteUrl(f.id), fileName = f.name, sizeBytes = f.sizeBytes, label = f.name, isExtra = true)
            } + game.updates.map { f ->
                ScrapedDownload(url = remoteUrl(f.id), fileName = f.name, sizeBytes = f.sizeBytes, label = DriveNaming.addOnLabel(f.name), isUpdate = true)
            },
            updatedAt = game.modifiedAt?.toString(),
        )
    }

    /** Checks the file is still there and returns its address with the real name and size. */
    override suspend fun resolveDownload(url: String, referer: String?): DownloadInfo {
        val remote = RemoteUrl.parse(url)?.takeIf(::acceptsRemoteUrl)
            ?: throw ScraperException.InvalidContent(url, "not a file of this server")
        val entry = withSession { session -> io { session.stat(remote.path) } } ?: throw ScraperException.Http(url, 404)
        if (entry.isDirectory) throw ScraperException.InvalidContent(url, "not a downloadable file")
        return DownloadInfo(url = remote.toString(), fileName = entry.name, sizeBytes = entry.sizeBytes, contentType = null, sourcePage = referer ?: remote.toString())
    }

    override suspend fun openFile(url: String, offset: Long): RemoteFile {
        val remote = RemoteUrl.parse(url)?.takeIf(::acceptsRemoteUrl) ?: throw ScraperException.InvalidContent(url, "not a file of this server")
        val session = connector.connect()
        return try {
            withContext(Dispatchers.IO) { session.open(remote.path, offset) }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { session.close() }
            throw e
        }
    }

    /** Only HTTP is ruled out here: this source speaks FTP/SFTP, see [acceptsRemoteUrl]. */
    override fun acceptsDownloadUrl(url: HttpUrl): Boolean = false

    /** The same server, protocol and port, inside the folder the source reads. */
    override fun acceptsRemoteUrl(url: RemoteUrl): Boolean =
        url.protocol == config.protocol && url.host.equals(config.host, ignoreCase = true) && url.port == config.effectivePort &&
            (rootPath == "/" || url.path == rootPath || url.path.startsWith("$rootPath/"))

    /** Everything is synced locally; the store's local search finds it. */
    override suspend fun search(query: String): List<ScrapedGame> = emptyList()

    // --- helpers -------------------------------------------------------------------------------

    /** One connection for the duration of [block]. */
    private suspend fun <T> withSession(block: suspend (RemoteSession) -> T): T {
        val session = connector.connect()
        try {
            return block(session)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { session.close() }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * The walker lists several batches at the same time, but a connection serves one command at a time:
     * listings are serialized, spaced by the source's interval, and counted against the budget.
     */
    private fun walker(session: RemoteSession, budget: Budget): DriveCatalogWalker {
        val lock = Mutex()
        var last = 0L
        return DriveCatalogWalker(
            list = { folders ->
                lock.withLock {
                    val result = LinkedHashMap<String, List<DriveFile>>()
                    for (folder in folders) {
                        if (!budget.take()) throw DriveBudgetExhausted()
                        val wait = last + config.minRequestIntervalMs - System.currentTimeMillis()
                        if (wait > 0) delay(wait)
                        result[folder.id] = try {
                            withContext(Dispatchers.IO) { session.list(folder.id) }.map { it.toFile(folder.id) }
                        } catch (e: ScraperException.AccessDenied) {
                            // A folder the account may not open stays closed; the rest of the tree is still read.
                            if (folder.id == rootPath) throw e
                            skipped.incrementAndGet()
                            log.warn("Skipping folder '${folder.name}': ${e.message}")
                            emptyList()
                        }
                        last = System.currentTimeMillis()
                    }
                    result
                }
            },
            maxDepth = config.maxDepth,
        )
    }

    /** The add-on folder of a game: one the account cannot open is left out. */
    private fun readable(session: RemoteSession, folder: DriveFile): List<DriveFile> = try {
        session.list(folder.id).map { it.toFile(folder.id) }
    } catch (e: ScraperException.AccessDenied) {
        log.warn("Skipping folder '${folder.name}': ${e.message}")
        emptyList()
    }

    private fun RemoteEntry.toFile(parent: String) = DriveFile(
        id = path,
        name = name,
        mimeType = if (isDirectory) DriveFile.FOLDER_MIME else "application/octet-stream",
        size = sizeBytes?.toString(),
        modifiedTime = modifiedAt?.toString(),
        parents = listOf(parent),
    )

    private fun DriveGame.toScraped() = ScrapedGame(id = id, title = title, platform = platform, sizeBytes = sizeBytes)

    private fun remoteUrl(path: String) = rootUrl.withPath(path).toString()

    /** A game id is a path on this server; anything that climbs out of the root is refused. */
    private fun checkedPath(id: String): String {
        val path = RemoteUrl.normalizePath(id) ?: throw ScraperException.InvalidContent(id, "invalid path")
        if (!(rootPath == "/" || path == rootPath || path.startsWith("$rootPath/"))) throw ScraperException.InvalidContent(id, "outside the source's folder")
        return path
    }

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    private fun rootName(): String = rootPath.trimEnd('/').substringAfterLast('/').ifEmpty { config.host }

    private class Budget(private val max: Int) {
        private val used = AtomicInteger()
        fun take(): Boolean = used.incrementAndGet() <= max
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
