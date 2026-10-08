package com.rshop.data.source

import com.rshop.data.sync.SyncScheduler
import com.rshop.data.sync.SyncStatusStore
import com.rshop.data.sync.toDomain
import com.rshop.domain.repository.GameRepository
import com.rshop.scraper.analysis.SiteAnalysis
import com.rshop.scraper.config.ScraperConfig
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Adding, updating and removing catalogue sources, keeping the database consistent. */
@Singleton
class SourceManager @Inject constructor(
    private val sources: SourceRepository,
    private val games: GameRepository,
    private val scheduler: SyncScheduler,
    private val status: SyncStatusStore,
    private val clock: Clock,
) {
    val configs = sources.configs

    /** Reads the site (robots.txt and rate limit apply) and proposes a configuration. */
    suspend fun analyze(url: String): SiteAnalysis = sources.analyzer().analyze(url)

    /** Adds a source, or updates the one of the same site, then syncs it. */
    suspend fun add(config: ScraperConfig) {
        val previous = sources.get(config.id)
        sources.save(config)
        if (previous == null) status.remove(config.id)
        // The demo catalogue goes away with the first real source.
        games.deleteGamesNotFrom(sources.all().map { it.id })
        // New or changed rules: read everything again.
        scheduler.syncNow(config.id, restart = true, full = true)
    }

    /** Validates and adds a hand-written configuration. */
    suspend fun importJson(json: String): ScraperConfig = ScraperConfig.fromJson(json).also { add(it) }

    suspend fun exportJson(sourceId: String): String? = sources.get(sourceId)?.toJson()

    /** Removes the source and its games (favorites on them too); installed files stay. */
    suspend fun remove(sourceId: String) {
        scheduler.cancel(sourceId)
        sources.remove(sourceId)
        status.remove(sourceId)
        games.deleteGamesFrom(sourceId)
    }

    suspend fun syncNow(sourceId: String, full: Boolean = false) = scheduler.syncNow(sourceId, restart = full, full = full)

    /** Stops that source's sync; games already read are kept. */
    fun stopSync(sourceId: String) = scheduler.cancel(sourceId)

    /**
     * Asks every source that has a site search and stores the hits in the local catalogue, where
     * the store's local search then finds them. Returns the number of games found, or null when
     * no source has a search. A failing site does not stop the others.
     */
    suspend fun searchRemote(query: String): Int? {
        val searchable = sources.all().filter { it.searchUrl != null }
        if (searchable.isEmpty()) return null
        var found = 0
        var lastError: Exception? = null
        for (config in searchable) {
            try {
                val results = sources.createSource(config).search(query)
                games.saveListing(results.map { it.toDomain(config.id) }, clock.instant())
                found += results.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Search on %s failed", config.id)
                lastError = e
            }
        }
        if (found == 0 && lastError != null) throw lastError
        return found
    }
}
