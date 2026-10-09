package com.rshop.data.artwork

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rshop.data.metadata.GameMetadata
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches missing covers in the background, in rounds short enough for a plain worker; a
 * round that leaves games behind schedules the next one.
 */
@HiltWorker
class ArtworkWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val resolver: ArtworkResolver,
    private val metadata: GameMetadata,
    private val scheduler: ArtworkScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        return try {
            while (System.currentTimeMillis() - started < ROUND_MS) {
                // Covers, then screenshots and descriptions found outside the catalogue source.
                val covers = resolver.resolvePending(BATCH)
                val infos = metadata.resolvePending(BATCH)
                if (covers < BATCH && infos < BATCH) return Result.success()
            }
            scheduler.schedule(continuation = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArtworkException.InvalidKey) {
            Timber.w(e, "SteamGridDB key rejected")
            Result.failure()
        } catch (e: IOException) {
            Timber.w(e, "Cover lookup interrupted")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val BATCH = 60
        /** Well under WorkManager's 10-minute limit for plain workers. */
        const val ROUND_MS = 7 * 60_000L
        const val MAX_RETRIES = 3
    }
}

@Singleton
class ArtworkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** [continuation]: queued after the running round instead of being dropped as a duplicate. */
    fun schedule(continuation: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ArtworkWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** A new key: start over at once rather than after a round using the old one. */
    fun restart() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        schedule()
    }

    private companion object {
        const val WORK_NAME = "artwork"
    }
}
