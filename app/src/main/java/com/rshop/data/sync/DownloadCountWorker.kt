package com.rshop.data.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rshop.domain.repository.SettingsRepository
import com.rshop.scraper.ScraperException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the download counters missing from the catalogue, in rounds short enough for a plain
 * worker; a round that leaves games behind schedules the next one.
 */
@HiltWorker
class DownloadCountWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: DownloadCountSyncer,
    private val scheduler: DownloadCountScheduler,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val current = settings.settings.first()
        if (current.syncPaused) return Result.success()
        // A long job: it follows the "Wi-Fi only" setting like downloads do. It starts again with the next sync.
        if (current.wifiOnly && applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) {
            return Result.success()
        }
        val started = System.currentTimeMillis()
        return try {
            while (System.currentTimeMillis() - started < ROUND_MS) {
                if (syncer.refreshBatch(BATCH) < BATCH) return Result.success()
            }
            scheduler.schedule(continuation = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScraperException.Network) {
            retryOrFail(e)
        } catch (e: ScraperException.Busy) {
            retryOrFail(e)
        }
    }

    private fun retryOrFail(e: ScraperException): Result {
        Timber.w(e, "Download counters interrupted")
        return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    private companion object {
        const val BATCH = 30
        /** Well under WorkManager's 10-minute limit for plain workers. */
        const val ROUND_MS = 7 * 60_000L
        const val MAX_RETRIES = 3
    }
}

@Singleton
class DownloadCountScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** [continuation]: queued after the running round instead of being dropped as a duplicate. */
    fun schedule(continuation: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<DownloadCountWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "download-counts"
    }
}
