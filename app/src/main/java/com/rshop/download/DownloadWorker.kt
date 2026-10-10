package com.rshop.download

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rshop.BuildConfig
import com.rshop.R
import com.rshop.data.database.DownloadOptionsJson
import com.rshop.data.database.dao.DownloadDao
import com.rshop.data.database.entity.DownloadEntity
import com.rshop.data.source.SourceRepository
import com.rshop.data.sync.sourceIdOf
import com.rshop.data.work.AppNotifications
import com.rshop.domain.model.DownloadStatus
import com.rshop.domain.repository.SettingsRepository
import com.rshop.installation.ArchiveVolumes
import com.rshop.installation.GameInstaller
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.Clock

/**
 * One game's pipeline: (download page → wait) → download with resume → SHA-256 check →
 * extraction into the games folder. Runs as a data-sync foreground job with a progress
 * notification. Each step is skipped when an earlier run already completed it.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DownloadDao,
    private val installer: GameInstaller,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val tracker: DownloadProgressTracker,
    private val notifications: AppNotifications,
    private val clock: Clock,
    private val slots: DownloadSlots,
    private val browserStreams: BrowserStreams,
    okHttpClient: OkHttpClient,
) : CoroutineWorker(context, params) {

    private val gameId = requireNotNull(params.inputData.getString(KEY_GAME_ID))
    private val notificationId = AppNotifications.downloadNotificationId(gameId)
    private val downloader = HttpFileDownloader(
        // No shared cookie jar: a browser download carries its own Cookie header.
        client = okHttpClient.newBuilder().cache(null).cookieJar(CookieJar.NO_COOKIES).build(),
        userAgent = "RShop/${BuildConfig.VERSION_NAME} (Android)",
    )
    private var lastDbWrite = 0L
    private var lastNotification = 0L

    override suspend fun doWork(): Result {
        var row = dao.get(gameId) ?: return Result.success()
        if (!DownloadStatus.valueOf(row.state).isActive) return Result.success()
        runCatching { setForeground(foreground(row.title, null)) }
            .onFailure { Timber.w(it, "Download runs without foreground service") }

        return try {
            // A game made of several files goes through the same steps for each one, in order;
            // every file is installed before the next is fetched.
            while (true) {
                row = processFile(row)
                row = nextPart(row) ?: break
            }
            complete(row)
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Stopped by the system (network lost, constraints): back in the queue. A user
                // pause or cancel already changed or removed the row.
                val current = dao.get(gameId)
                if (current != null && DownloadStatus.valueOf(current.state).isActive) {
                    dao.updateState(gameId, DownloadStatus.Queued.name, null, clock.millis())
                }
                tracker.clear(gameId)
            }
            throw e
        } catch (e: Exception) {
            val error = e.toDownloadError()
            Timber.w(e, "Download of %s failed (%s)", gameId, error.kind)
            tracker.clear(gameId)
            if (error.isTransient && runAttemptCount < MAX_TRANSIENT_RETRIES) {
                dao.updateState(gameId, DownloadStatus.Queued.name, error.encode(), clock.millis())
                Result.retry()
            } else {
                dao.updateState(gameId, DownloadStatus.Failed.name, error.encode(), clock.millis())
                notifyDone(row.title, success = false)
                Result.failure()
            }
        }
    }

    /** Download (or copy), check and install the file of [start]; returns the row as it ended. */
    private suspend fun processFile(start: DownloadEntity): DownloadEntity {
        var row = start
        // The path can change once: a download page resolves to the real file name.
        var file = File(row.tempPath)
        if (row.url.startsWith(BrowserStreams.SCHEME)) {
            // Already flowing from the browser: never held back by the parallel-download limit.
            row = copyBrowserStream(row, file)
        } else if (!isDownloaded(row, file)) {
            // Only the transfer is limited; verification and installation run freely.
            row = slots.semaphore.withPermit { download(row, file) }
            file = File(row.tempPath)
        }
        if ((row.expectedSha256 != null || row.expectedMd5 != null) && !row.verified) {
            row = verify(row, file)
        }
        // A volume of a split archive ("Game.part1.rar") waits for the others: the archive is
        // extracted once, from its first volume, when the last one is downloaded.
        if (ArchiveVolumes.waitsForMore(row.fileName, plannedNames(row))) {
            Timber.i("Volume %s kept until the archive is complete", row.fileName)
            return row
        }
        val volumes = ArchiveVolumes.siblings(file)
        // The first file installed of this game: the volumes before it installed nothing.
        val installRow = if (volumes.size > 1) row.copy(partIndex = (row.partIndex - (volumes.size - 1)).coerceAtLeast(0)) else row
        install(installRow, volumes.first())
        return row
    }

    /** Names of the game's files still to fetch after [row]: the planned ones, then those added meanwhile. */
    private suspend fun plannedNames(row: DownloadEntity): List<String> =
        (DownloadOptionsJson.decode(row.extraParts) + dao.additions(gameId).flatMap { DownloadOptionsJson.decode(it.parts) })
            .map { it.fileName ?: it.url.substringBefore('?').substringAfterLast('/') }

    /** The row for the next file of the game, saved, or null when [row] was the last one. */
    private suspend fun nextPart(row: DownloadEntity): DownloadEntity? {
        // Files the player added meanwhile (an update, a DLC) come after the ones already planned.
        val added = dao.additions(gameId)
        added.lastOrNull()?.let { dao.deleteAdditions(gameId, it.id) }
        val parts = DownloadOptionsJson.decode(row.extraParts) + added.flatMap { DownloadOptionsJson.decode(it.parts) }
        val next = parts.firstOrNull() ?: return null
        // The file just installed is not needed any more (unless the user keeps archives). The volumes
        // of a split archive stay until its last one is in and the whole archive is extracted.
        if (settings.settings.first().deleteArchivesAfterInstall) {
            val remaining = parts.map { it.fileName ?: it.url.substringBefore('?').substringAfterLast('/') }
            if (!ArchiveVolumes.waitsForMore(row.fileName, remaining)) ArchiveVolumes.siblings(File(row.tempPath)).forEach { it.delete() }
        }
        var fileName = DownloadPolicy.fileName(next.url.toHttpUrl(), row.title)
        if (fileName == row.fileName) fileName = "${row.partIndex + 2}-$fileName"
        val advanced = row.copy(
            url = next.url,
            viaPage = true,
            fileName = fileName,
            tempPath = File(File(row.tempPath).parentFile, fileName).absolutePath,
            expectedSha256 = next.sha256,
            expectedMd5 = null,
            totalBytes = next.sizeBytes,
            downloadedBytes = 0,
            etag = null,
            lastModified = null,
            state = DownloadStatus.Downloading.name,
            error = null,
            verified = false,
            referer = null,
            requestCookie = null,
            requestUserAgent = null,
            extraParts = DownloadOptionsJson.encode(parts.drop(1)),
            partIndex = row.partIndex + 1,
            partCount = row.partIndex + 1 + parts.size,
            updatedAt = clock.millis(),
        )
        dao.upsert(advanced)
        tracker.clear(gameId)
        Timber.i("Next file of %s (%d/%d)", gameId, advanced.partIndex + 1, advanced.partCount)
        return advanced
    }

    override suspend fun getForegroundInfo() = foreground(dao.get(gameId)?.title ?: "", null)

    private fun isDownloaded(row: DownloadEntity, file: File): Boolean =
        file.exists() && row.totalBytes != null && file.length() == row.totalBytes && row.downloadedBytes == row.totalBytes

    private suspend fun download(start: DownloadEntity, file: File): DownloadEntity {
        var row = start
        dao.updateState(gameId, DownloadStatus.Downloading.name, null, clock.millis())

        // A file the user downloaded themselves: copy it into app storage, no network.
        if (row.url.startsWith("content://")) return copyLocal(row, file)

        // Unresolved link: the scraper follows download pages (waiting as asked) and redirects until
        // a URL answers with a file, read from its headers only. It never passes a CAPTCHA or login.
        if (row.viaPage) {
            val config = sources.get(sourceIdOf(row.gameId)) ?: throw DownloadException.Rejected("The game's source was removed")
            val source = sources.createSource(config)
            // The game page is where the first link was clicked.
            val gamePage = source.gamePageUrl(row.gameId.substringAfter(':'))
            val info = source.resolveDownload(row.url, referer = gamePage)
            val url = info.url.toHttpUrl()
            val fileName = DownloadPolicy.fileName(info.fileName, url, row.title, info.contentType)
            DownloadPolicy.check(url, fileName) { source.acceptsDownloadUrl(it) }
            DownloadPolicy.checkContentType(info.contentType)
            val target = File(file.parentFile, fileName)
            row = row.copy(
                url = info.url,
                viaPage = false,
                fileName = fileName,
                tempPath = target.absolutePath,
                totalBytes = info.sizeBytes ?: row.totalBytes,
                referer = info.sourcePage,
                expectedMd5 = info.md5 ?: row.expectedMd5,
            )
            dao.upsert(row)
            return download(row, target)
        }

        val validators = ResumeValidators(row.etag, row.lastModified).takeIf { it.ifRange != null }
        val margin = settings.settings.first().minFreeSpaceMb * BYTES_PER_MB
        checkGamesFolderSpace(row, margin)
        val outcome = downloader.download(
            spaceMargin = margin,
            url = row.url.toHttpUrl(),
            target = file,
            referer = row.referer,
            session = if (row.requestCookie != null || row.requestUserAgent != null) {
                BrowserSession(row.requestCookie, row.requestUserAgent)
            } else {
                null
            },
            validators = validators,
            onStarted = { newValidators, total ->
                row = row.copy(etag = newValidators.etag, lastModified = newValidators.lastModified, totalBytes = total ?: row.totalBytes)
                dao.upsert(row.copy(state = DownloadStatus.Downloading.name, updatedAt = clock.millis()))
            },
            onProgress = { downloaded, total -> onDownloadProgress(row.title, downloaded, total) },
        )
        dao.updateProgress(gameId, outcome.totalBytes, outcome.totalBytes, clock.millis())
        val done = dao.get(gameId) ?: throw CancellationException("Download removed")
        if (done.requestCookie == null) return done
        // The browser cookies are not kept beyond the file they were given for.
        return done.copy(requestCookie = null).also { dao.upsert(it) }
    }

    /**
     * The file is extracted into the games folder, usually on another volume than the download
     * folder: fail now, not after the whole transfer, when it cannot fit there.
     */
    private suspend fun checkGamesFolderSpace(row: DownloadEntity, margin: Long) {
        val total = row.totalBytes ?: return
        val free = installer.deviceSpace(row)?.freeBytes ?: return
        if (total + margin > free) throw DownloadException.InsufficientStorage(total + margin, free)
    }

    /** Streams a user-picked file (content://) into [file], reporting progress like a download. */
    private suspend fun copyLocal(row: DownloadEntity, file: File): DownloadEntity {
        val uri = android.net.Uri.parse(row.url)
        val resolver = applicationContext.contentResolver
        val total = row.totalBytes ?: runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { len -> len >= 0 } }
        }.getOrNull()
        file.parentFile?.mkdirs()
        withContext(Dispatchers.IO) {
            val input = resolver.openInputStream(uri) ?: throw DownloadException.Rejected("The chosen file could not be opened")
            input.use { source ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        onDownloadProgress(row.title, copied, total)
                    }
                    if (copied == 0L) throw DownloadException.Rejected("The chosen file is empty")
                }
            }
        }
        runCatching {
            resolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val size = file.length()
        val done = row.copy(url = file.toURI().toString(), totalBytes = size, downloadedBytes = size)
        dao.upsert(done)
        dao.updateProgress(gameId, size, size, clock.millis())
        return done
    }

    /** Reads the body the in-app browser handed over (see [BrowserStreams]) into [file]. */
    private suspend fun copyBrowserStream(row: DownloadEntity, file: File): DownloadEntity {
        val handle = browserStreams.take(gameId) ?: throw DownloadException.StreamLost()
        dao.updateState(gameId, DownloadStatus.Downloading.name, null, clock.millis())
        try {
            file.parentFile?.mkdirs()
            withContext(Dispatchers.IO) {
                handle.body.use { source ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            onDownloadProgress(row.title, copied, row.totalBytes)
                        }
                        if (copied == 0L) throw DownloadException.Rejected("The browser handed over an empty file")
                    }
                }
            }
        } catch (e: IOException) {
            file.delete()
            // Cut mid-way: the body cannot be requested again, so no automatic retry.
            throw if (e is DownloadException) e else DownloadException.StreamLost()
        } finally {
            handle.release()
        }
        val size = file.length()
        if (row.totalBytes != null && size != row.totalBytes) {
            file.delete()
            throw DownloadException.StreamLost()
        }
        val done = row.copy(url = file.toURI().toString(), totalBytes = size, downloadedBytes = size)
        dao.upsert(done)
        dao.updateProgress(gameId, size, size, clock.millis())
        return done
    }

    private suspend fun verify(row: DownloadEntity, file: File, retried: Boolean = false): DownloadEntity {
        dao.updateState(gameId, DownloadStatus.Verifying.name, null, clock.millis())
        runCatching { setForeground(foreground(row.title, null, verifying = true)) }
        try {
            // The SHA-256 published by the source when there is one, else the server's MD5.
            val sha256 = row.expectedSha256
            if (sha256 != null) IntegrityVerifier.verify(file, sha256) else IntegrityVerifier.verifyMd5(file, row.expectedMd5!!)
        } catch (e: DownloadException.ChecksumMismatch) {
            // Corrupted or changed file: delete it and download again once from scratch.
            Timber.w("Checksum mismatch for %s (retried=%s)", gameId, retried)
            file.delete()
            val reset = row.copy(downloadedBytes = 0, etag = null, lastModified = null, verified = false)
            dao.upsert(reset)
            if (retried) throw e
            return verify(download(reset, file), file, retried = true)
        }
        val verified = (dao.get(gameId) ?: row).copy(verified = true, state = DownloadStatus.Verifying.name)
        dao.upsert(verified)
        Timber.i("Checksum verified for %s", gameId)
        return verified
    }

    private suspend fun install(row: DownloadEntity, file: File) {
        dao.updateState(gameId, DownloadStatus.Installing.name, null, clock.millis())
        runCatching { setForeground(foreground(row.title, null, installing = true)) }
        installer.install(row, file) { written -> tracker.onInstall(gameId, written) }
    }

    /** Every file of the game is installed. */
    private suspend fun complete(row: DownloadEntity) {
        dao.updateState(gameId, DownloadStatus.Completed.name, null, clock.millis())
        tracker.clear(gameId)
        if (settings.settings.first().deleteArchivesAfterInstall) {
            File(row.tempPath).parentFile?.deleteRecursively()
        }
        notifyDone(row.title, success = true)
        Timber.i("Installed %s", gameId)
    }

    private suspend fun onDownloadProgress(title: String, downloaded: Long, total: Long?) {
        tracker.onDownload(gameId, downloaded, total)
        val now = SystemClock.elapsedRealtime()
        if (now - lastDbWrite >= DB_WRITE_INTERVAL_MS) {
            lastDbWrite = now
            dao.updateProgress(gameId, downloaded, total, clock.millis())
        }
        if (now - lastNotification >= NOTIFICATION_INTERVAL_MS) {
            lastNotification = now
            val percent = total?.takeIf { it > 0 }?.let { (downloaded * 100 / it).toInt() }
            runCatching { setForeground(foreground(title, percent)) }
        }
    }

    private fun foreground(title: String, percent: Int?, verifying: Boolean = false, installing: Boolean = false) =
        notifications.foregroundInfo(
            id = notificationId,
            channel = AppNotifications.CHANNEL_DOWNLOADS,
            title = title,
            text = applicationContext.getString(
                when {
                    verifying -> R.string.download_status_verifying
                    installing -> R.string.download_status_installing
                    else -> R.string.download_status_downloading
                },
            ),
            progress = percent,
            // Pause and Cancel while bytes are moving; checking and extracting are not interrupted.
            gameId = gameId.takeIf { !verifying && !installing },
        )

    private fun notifyDone(title: String, success: Boolean) {
        notifications.notifyFinished(
            id = notificationId,
            title = title,
            text = applicationContext.getString(if (success) R.string.download_done_installed else R.string.download_done_failed),
        )
    }

    companion object {
        const val KEY_GAME_ID = "game_id"
        private const val MAX_TRANSIENT_RETRIES = 8
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val DB_WRITE_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}
