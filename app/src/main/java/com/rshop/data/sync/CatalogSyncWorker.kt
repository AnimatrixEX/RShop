package com.rshop.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rshop.R
import com.rshop.data.artwork.ArtworkScheduler
import com.rshop.data.source.SourceRepository
import com.rshop.data.work.AppNotifications
import com.rshop.scraper.ScraperException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs a full catalogue sync of one source in the background. Large sites take longer than
 * WorkManager's 10-minute limit for plain workers, so it runs as a (silent) foreground service.
 */
@HiltWorker
class CatalogSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: CatalogSyncer,
    private val status: SyncStatusStore,
    private val notifications: AppNotifications,
    private val artworkScheduler: ArtworkScheduler,
    private val sources: SourceRepository,
) : CoroutineWorker(context, params) {

    private val sourceId = params.inputData.getString(KEY_SOURCE_ID).orEmpty()
    // One notification per source: several sources can sync at the same time.
    private val notificationId = AppNotifications.SYNC_NOTIFICATION_ID + 100 + (sourceId.hashCode() and 0xFFFF)
    private var sourceName: String? = null

    override suspend fun doWork(): Result {
        sourceName = sources.get(sourceId)?.name ?: return Result.success() // removed meanwhile
        try {
            setForeground(foreground(null))
        } catch (e: IllegalStateException) {
            // Foreground start can be refused (background restrictions): carry on as a normal worker.
            Timber.w(e, "Sync runs without foreground service")
        }
        return try {
            val count = syncer.sync(sourceId) { progress ->
                setProgress(workDataOf(KEY_PAGES to progress.pages, KEY_GAMES to progress.games, KEY_SECTION to progress.section))
                runCatching { setForeground(foreground(progress)) }
            }
            status.recordSuccess(sourceId, count)
            // New games have no cover yet: SteamGridDB is asked for them.
            artworkScheduler.schedule()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Catalogue sync failed")
            status.recordError(sourceId, e.toSourceError())
            // Only transient network problems are worth an automatic retry.
            if ((e is ScraperException.Network) && runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo() = foreground(null)

    private fun foreground(progress: SyncProgress?) = notifications.foregroundInfo(
        id = notificationId,
        channel = AppNotifications.CHANNEL_SYNC,
        title = sourceName?.let { applicationContext.getString(R.string.notif_sync_title_source, it) }
            ?: applicationContext.getString(R.string.notif_sync_title),
        text = progress?.let { applicationContext.resources.getQuantityString(R.plurals.sync_progress_games, it.games, it.games) },
        progress = null,
    )

    companion object {
        const val KEY_PAGES = "pages"
        const val KEY_GAMES = "games"
        const val KEY_SECTION = "section"
        const val KEY_SOURCE_ID = "source_id"
        private const val MAX_RETRIES = 2
    }
}
