package com.rshop.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rshop.data.source.SourceRepository
import com.rshop.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SyncState(
    val running: Boolean = false,
    val pages: Int = 0,
    val games: Int = 0,
    val section: String? = null,
    val lastSuccessAt: Instant? = null,
    val lastGameCount: Int = 0,
    val lastError: SourceError? = null,
)

/**
 * Catalogue syncs: one background job per source (sites are independent, each with its own
 * rate limit), seen by the UI as one overall [state] plus a state per source.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val status: SyncStatusStore,
    private val sources: SourceRepository,
    private val clock: Clock,
    private val settings: SettingsRepository,
    private val downloadCounts: DownloadCountScheduler,
) {
    private val workManager by lazy { WorkManager.getInstance(context) }

    /** Sync state of each source, by source id. */
    val sourceStates: Flow<Map<String, SyncState>> = combine(
        workManager.getWorkInfosByTagFlow(TAG),
        status.records,
        sources.configs,
    ) { infos, records, configs ->
        configs.associate { config ->
            val info = infos.lastOrNull { config.id in it.sourceIds() && !it.state.isFinished }
            val record = records[config.id]
            config.id to SyncState(
                running = info != null,
                pages = info?.progress?.getInt(CatalogSyncWorker.KEY_PAGES, 0) ?: 0,
                games = info?.progress?.getInt(CatalogSyncWorker.KEY_GAMES, 0) ?: 0,
                section = info?.progress?.getString(CatalogSyncWorker.KEY_SECTION),
                lastSuccessAt = record?.lastSuccessAt,
                lastGameCount = record?.lastGameCount ?: 0,
                lastError = record?.lastError,
            )
        }
    }

    /** All sources together: running while any runs; the last error of any source. */
    val state: Flow<SyncState> = sourceStates.map { states ->
        val list = states.values
        SyncState(
            running = list.any { it.running },
            pages = list.sumOf { it.pages },
            games = list.sumOf { it.games },
            section = list.firstOrNull { it.running }?.section,
            lastSuccessAt = list.mapNotNull { it.lastSuccessAt }.maxOrNull(),
            lastGameCount = list.sumOf { it.lastGameCount },
            lastError = list.firstNotNullOfOrNull { it.lastError },
        )
    }

    /**
     * Syncs [sourceId], or every source. [restart]: cancel a running sync first (source changed).
     * [full]: rescan the whole catalogue; otherwise a source already scanned only looks for new games.
     */
    suspend fun syncNow(sourceId: String? = null, restart: Boolean = false, full: Boolean = false) {
        val ids = sourceId?.let(::listOf) ?: sources.all().map { it.id }
        ids.forEach { id -> enqueue(id, restart, full) }
    }

    /** Stops the sync of [sourceId], or every sync. Games already read are kept. */
    fun cancel(sourceId: String? = null) {
        if (sourceId == null) workManager.cancelAllWorkByTag(TAG) else workManager.cancelUniqueWork(workName(sourceId))
    }

    /** True while the user has switched synchronisation off "until further notice". */
    val paused: Flow<Boolean> = settings.settings.map { it.syncPaused }.distinctUntilChanged()

    suspend fun isPaused(): Boolean = paused.first()

    /**
     * Switches automatic synchronisation off or on. Pausing also stops what is running; the
     * "Sync" buttons of the sources still work, as they are explicit requests.
     */
    suspend fun setPaused(paused: Boolean) {
        settings.setSyncPaused(paused)
        if (paused) {
            cancel()
            downloadCounts.cancel()
        }
    }

    /**
     * Startup sync: the UI shows the local catalogue at once and is refreshed when this ends.
     * Returns whether a sync was queued.
     */
    suspend fun syncIfStale(maxAge: Duration = DEFAULT_MAX_AGE): Boolean {
        val records = status.records.first()
        var enqueued = false
        sources.all().forEach { config ->
            val last = records[config.id]?.lastSuccessAt
            if (last == null || Duration.between(last, clock.instant()) > maxAge) {
                enqueue(config.id, restart = false, full = false)
                enqueued = true
            }
        }
        return enqueued
    }

    private fun enqueue(sourceId: String, restart: Boolean, full: Boolean) {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            .setInputData(workDataOf(CatalogSyncWorker.KEY_SOURCE_ID to sourceId, CatalogSyncWorker.KEY_FULL to full))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG)
            .addTag(SOURCE_TAG_PREFIX + sourceId)
            .build()
        workManager.enqueueUniqueWork(workName(sourceId), if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun WorkInfo.sourceIds() = tags.mapNotNull { tag -> tag.takeIf { it.startsWith(SOURCE_TAG_PREFIX) }?.removePrefix(SOURCE_TAG_PREFIX) }

    private fun workName(sourceId: String) = "catalog-sync:$sourceId"

    private companion object {
        const val TAG = "catalog-sync"
        const val SOURCE_TAG_PREFIX = "catalog-sync-source:"
        val DEFAULT_MAX_AGE: Duration = Duration.ofHours(6)
    }
}
