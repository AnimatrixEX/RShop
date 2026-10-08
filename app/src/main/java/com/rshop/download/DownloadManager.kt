package com.rshop.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rshop.data.database.dao.DownloadDao
import com.rshop.data.database.entity.DownloadEntity
import com.rshop.data.source.SourceRepository
import com.rshop.data.storage.GamesDirectoryManager
import com.rshop.data.storage.GamesDirectoryState
import com.rshop.data.sync.CatalogSyncer
import com.rshop.domain.model.DownloadError
import com.rshop.domain.model.DownloadErrorKind
import com.rshop.domain.model.DownloadOption
import com.rshop.domain.model.DownloadStatus
import com.rshop.domain.model.DownloadTask
import com.rshop.domain.model.Game
import com.rshop.domain.model.sourceId
import com.rshop.domain.repository.GameRepository
import com.rshop.domain.repository.SettingsRepository
import com.rshop.scraper.website.DownloadUrlPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StartResult {
    data object Started : StartResult
    data object NoGamesDirectory : StartResult
    /** The file is behind a site page: the user opens it in the in-app browser and clicks the link. */
    data class OpenInBrowser(val url: String) : StartResult
    data class Failed(val error: DownloadError) : StartResult
}

/** A download the user started from a page of the in-app browser. */
data class BrowserFile(
    val url: String,
    val fileName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val pageUrl: String?,
    val cookie: String?,
    val userAgent: String?,
)

/**
 * Download queue: what the UI calls. Work itself runs in [DownloadWorker] (one unique WorkManager
 * job per game), which survives the app going to the background and device reboots.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val games: GameRepository,
    private val syncer: CatalogSyncer,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val directoryManager: GamesDirectoryManager,
    private val tracker: DownloadProgressTracker,
    private val clock: Clock,
    private val browserStreams: BrowserStreams,
) {
    private val workManager by lazy { WorkManager.getInstance(context) }

    /** App-specific storage: large, no permission needed, cleaned on uninstall. */
    val downloadsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    fun observeTasks(): Flow<List<DownloadTask>> =
        combine(dao.observeAll(), tracker.live) { rows, live -> rows.map { it.toTask(live[it.gameId]) } }

    fun observeTask(gameId: String): Flow<DownloadTask?> =
        combine(dao.observe(gameId), tracker.live) { row, live -> row?.toTask(live[gameId]) }

    /**
     * Queues the game's download. [optionUrl] picks one of [Game.downloadOptions] (format, disc…);
     * null takes the first one.
     */
    suspend fun start(gameId: String, optionUrl: String? = null): StartResult {
        dao.get(gameId)?.let { existing ->
            if (DownloadStatus.valueOf(existing.state).isActive) return StartResult.Started
        }
        if (directoryManager.state.first() !is GamesDirectoryState.Available) return StartResult.NoGamesDirectory

        val game = try {
            gameWithDownloadLink(gameId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Cannot read game page for %s", gameId)
            return StartResult.Failed(e.toDownloadError())
        } ?: return StartResult.Failed(DownloadError(DownloadErrorKind.NoLink))

        val option = game.downloadOptions.firstOrNull { it.url == optionUrl }
            ?: game.downloadOptions.firstOrNull()
            ?: game.downloadUrl?.let { DownloadOption(url = it, sha256 = game.sha256, sizeBytes = game.sizeBytes, viaPage = game.downloadViaPage) }
            ?: return StartResult.Failed(DownloadError(DownloadErrorKind.NoLink))
        val url = option.url.toHttpUrlOrNull() ?: return StartResult.Failed(DownloadError(DownloadErrorKind.NoLink))
        if (option.viaPage) return StartResult.OpenInBrowser(url.toString())
        val config = sources.get(game.sourceId)
            ?: return StartResult.Failed(DownloadError(DownloadErrorKind.Rejected, "game does not belong to a configured source"))
        val policy = DownloadUrlPolicy(config.base, config.allowedDownloadHosts)
        val fileName = DownloadPolicy.fileName(url, game.title)
        try {
            // Every link is resolved by the worker first (download pages, redirects, real file
            // name); the resolved URL and name are checked again there.
            DownloadPolicy.check(url, if (option.viaPage) "$fileName.page" else fileName) { policy.accepts(it) || option.viaPage }
        } catch (e: DownloadException) {
            return StartResult.Failed(e.toDownloadError())
        }

        val now = clock.millis()
        dao.upsert(
            DownloadEntity(
                gameId = game.id,
                title = game.title,
                platform = game.platform,
                coverUrl = game.coverUrl,
                url = url.toString(),
                // Always resolved before the transfer, even "direct" links: a link that answers
                // with a web page is followed like a download page instead of failing.
                viaPage = true,
                fileName = fileName,
                tempPath = File(File(downloadsDir, folderFor(game.id)), fileName).absolutePath,
                expectedSha256 = option.sha256,
                totalBytes = option.sizeBytes ?: game.sizeBytes.takeIf { game.downloadOptions.size <= 1 },
                downloadedBytes = 0,
                etag = null,
                lastModified = null,
                state = DownloadStatus.Queued.name,
                error = null,
                verified = false,
                version = game.version,
                createdAt = now,
                updatedAt = now,
            ),
        )
        enqueue(game.id)
        return StartResult.Started
    }

    /**
     * A file the user clicked in the in-app browser: downloaded and installed like any other,
     * with that browser's cookies and User-Agent so the site serves the same file.
     */
    suspend fun startFromBrowser(gameId: String, file: BrowserFile): StartResult {
        dao.get(gameId)?.let { existing ->
            if (DownloadStatus.valueOf(existing.state).isActive) return StartResult.Started
        }
        if (directoryManager.state.first() !is GamesDirectoryState.Available) return StartResult.NoGamesDirectory
        val game = games.getGame(gameId) ?: return StartResult.Failed(DownloadError(DownloadErrorKind.NoLink))
        val url = file.url.toHttpUrlOrNull() ?: return StartResult.Failed(DownloadError(DownloadErrorKind.NoLink))
        val fileName = DownloadPolicy.fileName(file.fileName, url, game.title, file.mimeType)
        try {
            // Any host: the user chose this link. Executables and scripts are still refused.
            DownloadPolicy.check(url, fileName) { true }
            DownloadPolicy.checkContentType(file.mimeType)
        } catch (e: DownloadException) {
            return StartResult.Failed(e.toDownloadError())
        }
        val now = clock.millis()
        dao.upsert(
            DownloadEntity(
                gameId = game.id,
                title = game.title,
                platform = game.platform,
                coverUrl = game.coverUrl,
                url = url.toString(),
                viaPage = false,
                fileName = fileName,
                tempPath = File(File(downloadsDir, folderFor(game.id)), fileName).absolutePath,
                expectedSha256 = null,
                totalBytes = file.sizeBytes,
                downloadedBytes = 0,
                etag = null,
                lastModified = null,
                state = DownloadStatus.Queued.name,
                error = null,
                verified = false,
                version = game.version,
                createdAt = now,
                updatedAt = now,
                referer = file.pageUrl,
                requestCookie = file.cookie,
                requestUserAgent = file.userAgent,
            ),
        )
        enqueue(game.id)
        return StartResult.Started
    }

    /**
     * Installs a file the user already downloaded (e.g. in the device browser, for a site that
     * refuses the in-app one): copied into app storage by the worker, then verified and installed
     * like any download. Any host/page is irrelevant here; executables and scripts are refused.
     */
    suspend fun installLocalFile(gameId: String, source: android.net.Uri, displayName: String?, sizeBytes: Long?): StartResult {
        if (directoryManager.state.first() !is GamesDirectoryState.Available) return StartResult.NoGamesDirectory
        val game = games.getGame(gameId) ?: return StartResult.Failed(DownloadError(DownloadErrorKind.NoLink))
        val fileName = com.rshop.installation.SafeEntryPath.sanitizeName(displayName ?: "${game.title}.zip", "${game.title}.zip")
        try {
            DownloadPolicy.checkLocalFile(fileName)
        } catch (e: DownloadException) {
            return StartResult.Failed(e.toDownloadError())
        }
        val now = clock.millis()
        dao.upsert(
            DownloadEntity(
                gameId = game.id,
                title = game.title,
                platform = game.platform,
                coverUrl = game.coverUrl,
                url = source.toString(),
                viaPage = false,
                fileName = fileName,
                tempPath = File(File(downloadsDir, folderFor(game.id)), fileName).absolutePath,
                expectedSha256 = null,
                totalBytes = sizeBytes,
                downloadedBytes = 0,
                etag = null,
                lastModified = null,
                state = DownloadStatus.Queued.name,
                error = null,
                verified = false,
                version = game.version,
                createdAt = now,
                updatedAt = now,
            ),
        )
        enqueue(game.id)
        return StartResult.Started
    }

    /**
     * A file the in-app browser is receiving: its live [body] is handed to the download worker,
     * which reads it in the foreground (so closing the browser does not cut it), then verifies and
     * installs it like any download. [release] closes the page session once the body is read.
     */
    suspend fun startBrowserStream(
        gameId: String,
        url: String,
        fileName: String,
        totalBytes: Long?,
        body: java.io.InputStream,
        release: () -> Unit,
    ): StartResult {
        val refuse = { result: StartResult ->
            runCatching { body.close() }
            release()
            result
        }
        if (directoryManager.state.first() !is GamesDirectoryState.Available) return refuse(StartResult.NoGamesDirectory)
        val game = games.getGame(gameId) ?: return refuse(StartResult.Failed(DownloadError(DownloadErrorKind.NoLink)))
        val name = com.rshop.installation.SafeEntryPath.sanitizeName(fileName, "${game.title}.zip")
        try {
            DownloadPolicy.checkLocalFile(name)
        } catch (e: DownloadException) {
            return refuse(StartResult.Failed(e.toDownloadError()))
        }
        // A previous attempt for this game (partial file, queued work) is replaced.
        workManager.cancelUniqueWork(workName(game.id))
        val folder = File(downloadsDir, folderFor(game.id))
        withContext(Dispatchers.IO) { folder.deleteRecursively() }
        browserStreams.put(game.id, BrowserStreams.Handle(body, release))
        val now = clock.millis()
        dao.upsert(
            DownloadEntity(
                gameId = game.id,
                title = game.title,
                platform = game.platform,
                coverUrl = game.coverUrl,
                url = BrowserStreams.SCHEME + url,
                viaPage = false,
                fileName = name,
                tempPath = File(folder, name).absolutePath,
                expectedSha256 = null,
                totalBytes = totalBytes,
                downloadedBytes = 0,
                etag = null,
                lastModified = null,
                state = DownloadStatus.Queued.name,
                error = null,
                verified = false,
                version = game.version,
                createdAt = now,
                updatedAt = now,
            ),
        )
        // The browser is already receiving the file: no Wi-Fi-only wait, it would stall the transfer.
        enqueue(game.id, anyNetwork = true)
        return StartResult.Started
    }

    suspend fun pause(gameId: String) {
        val row = dao.get(gameId) ?: return
        if (!DownloadStatus.valueOf(row.state).isActive) return
        // State first, so the cancelled worker knows it was a pause and keeps the partial file.
        dao.updateState(gameId, DownloadStatus.Paused.name, null, clock.millis())
        workManager.cancelUniqueWork(workName(gameId))
        tracker.clear(gameId)
    }

    suspend fun resume(gameId: String) {
        val row = dao.get(gameId) ?: return
        val status = DownloadStatus.valueOf(row.state)
        if (status != DownloadStatus.Paused && status != DownloadStatus.Failed) return
        dao.updateState(gameId, DownloadStatus.Queued.name, null, clock.millis())
        enqueue(gameId)
    }

    suspend fun retry(gameId: String) = resume(gameId)

    /** Stops and forgets the download, deleting its partial file. Installed files are untouched. */
    suspend fun cancel(gameId: String) {
        val row = dao.get(gameId) ?: return
        dao.delete(gameId)
        workManager.cancelUniqueWork(workName(gameId))
        browserStreams.discard(gameId)
        tracker.clear(gameId)
        withContext(Dispatchers.IO) { File(row.tempPath).parentFile?.deleteRecursively() }
    }

    /** Removes finished entries from the list (their archives were already handled). */
    suspend fun clearFinished() {
        dao.getByStates(listOf(DownloadStatus.Completed.name)).forEach { row ->
            dao.delete(row.gameId)
            withContext(Dispatchers.IO) { File(row.tempPath).parentFile?.deleteRecursively() }
        }
    }

    /** At startup: make sure every unfinished download has its job (KEEP leaves running ones alone). */
    suspend fun reconcile() {
        dao.getByStates(listOf(DownloadStatus.Queued.name, DownloadStatus.Downloading.name, DownloadStatus.Verifying.name, DownloadStatus.Installing.name))
            .forEach { enqueue(it.gameId, ExistingWorkPolicy.KEEP) }
    }

    private suspend fun gameWithDownloadLink(gameId: String): Game? {
        val game = games.getGame(gameId) ?: return null
        val stale = game.detailsSyncedAt == null ||
            clock.millis() - game.detailsSyncedAt.toEpochMilli() > TimeUnit.HOURS.toMillis(DETAILS_MAX_AGE_HOURS)
        if (game.downloadUrl != null && !stale) return game
        return syncer.refreshDetails(gameId) ?: game
    }

    private suspend fun enqueue(gameId: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE, anyNetwork: Boolean = false) {
        val wifiOnly = !anyNetwork && settings.settings.first().wifiOnly
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_GAME_ID to gameId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(gameId), policy, request)
    }

    private fun DownloadEntity.toTask(live: LiveProgress?): DownloadTask {
        val status = DownloadStatus.valueOf(state)
        val downloaded = live?.downloadedBytes ?: downloadedBytes
        val total = live?.totalBytes ?: totalBytes
        val speed = live?.bytesPerSecond?.takeIf { status == DownloadStatus.Downloading && it > 0 }
        return DownloadTask(
            gameId = gameId,
            title = title,
            platform = platform,
            coverUrl = coverUrl,
            fileName = fileName,
            status = status,
            downloadedBytes = downloaded,
            totalBytes = total,
            bytesPerSecond = speed,
            etaSeconds = if (speed != null && total != null) ((total - downloaded).coerceAtLeast(0) / speed) else null,
            installedBytes = live?.installedBytes,
            verified = verified,
            hasChecksum = expectedSha256 != null,
            error = DownloadError.decode(error),
        )
    }

    companion object {
        const val TAG = "download"
        private const val DETAILS_MAX_AGE_HOURS = 24L

        fun workName(gameId: String) = "download-${folderFor(gameId)}"

        /** Stable, filesystem-safe folder per game id. */
        fun folderFor(gameId: String): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(gameId.toByteArray())
                .take(10).joinToString("") { "%02x".format(it) }
    }
}
