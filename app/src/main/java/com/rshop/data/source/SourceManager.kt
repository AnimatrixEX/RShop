package com.rshop.data.source

import com.rshop.data.sync.SyncScheduler
import com.rshop.data.sync.SyncStatusStore
import com.rshop.data.sync.toDomain
import com.rshop.domain.repository.GameRepository
import com.rshop.scraper.analysis.SiteAnalysis
import com.rshop.scraper.config.DriveConfig
import com.rshop.scraper.config.ScraperConfig
import com.rshop.scraper.config.SourceConfig
import com.rshop.scraper.drive.DriveInspection
import com.rshop.scraper.drive.DriveLink
import com.rshop.scraper.model.CatalogSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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

    /**
     * Reads a shared Drive folder (its name and console folders) before it is added. Needs the
     * Drive API key. Throws [IllegalArgumentException] for a link that is not a Drive folder.
     */
    suspend fun inspectDrive(link: String): Pair<DriveConfig, DriveInspection> {
        val parsed = requireNotNull(DriveLink.parse(link)) { "Not a Google Drive folder link" }
        val draft = DriveConfig(DriveConfig.idFor(parsed.folderId), "Google Drive", parsed.folderId, parsed.resourceKey)
        val inspection = sources.createDriveSource(draft).inspect()
        return draft.copy(name = inspection.name.ifBlank { draft.name }) to inspection
    }

    /** Adds a source, or updates the one of the same site, then syncs it. */
    suspend fun add(config: SourceConfig) {
        val previous = sources.get(config.id)
        sources.save(config)
        if (previous == null) status.remove(config.id)
        // The demo catalogue goes away with the first real source.
        games.deleteGamesNotFrom(sources.all().map { it.id })
        // New or changed rules: read everything again.
        scheduler.syncNow(config.id, restart = true, full = true)
    }

    /** Validates and adds a hand-written configuration. */
    suspend fun importJson(json: String): SourceConfig = SourceConfig.fromJson(json).also { add(it) }

    suspend fun exportJson(sourceId: String): String? = sources.get(sourceId)?.toJson()

    /** Removes the source and its games (favorites on them too); installed files stay. */
    suspend fun remove(sourceId: String) {
        scheduler.cancel(sourceId)
        sources.remove(sourceId)
        status.remove(sourceId)
        games.deleteGamesFrom(sourceId)
    }

    suspend fun syncNow(sourceId: String, full: Boolean = false) = scheduler.syncNow(sourceId, restart = full, full = full)

    /** The consoles the site lists (one request), or empty for a source that has no console index. */
    suspend fun listConsoles(sourceId: String): List<CatalogSection> {
        val config = sources.get(sourceId) ?: return emptyList()
        return sources.createSource(config).sections()
    }

    /**
     * Chooses the consoles of a source: [enabled] holds the pages of the consoles to read out of
     * [all] (everything chosen means "no restriction", so consoles the site adds later are read
     * too). Consoles left out lose their catalogue entries at once, except favorites, listed and
     * installed games. A console added back is read now; the others are not scanned again.
     */
    suspend fun setConsoles(sourceId: String, all: List<CatalogSection>, enabled: Set<String>) {
        val config = sources.get(sourceId) ?: return
        val chosen = all.filter { it.url in enabled }
        require(chosen.isNotEmpty()) { "At least one console must stay enabled" }
        val before = config.enabledSections?.toSet()
        sources.save(config.withEnabledSections(if (chosen.size == all.size) null else chosen.map { it.url }))

        val left = all.filter { it.url !in enabled }
        if (left.isNotEmpty()) games.deleteGamesOfPlatforms(sourceId, left.map { it.name }.distinct())
        val added = before != null && chosen.any { it.url !in before }
        val running = scheduler.sourceStates.first()[sourceId]?.running == true
        // A running sync keeps the old choice until it restarts.
        if (added || running) scheduler.syncNow(sourceId, restart = true, full = false)
    }

    /**
     * Sets the console of every game of a Drive that has no console folders. Games already read
     * keep their old console until the sync that follows rewrites them.
     */
    suspend fun setDrivePlatform(sourceId: String, platform: String) {
        val config = sources.get(sourceId) as? DriveConfig ?: return
        require(platform.isNotBlank()) { "Choose a console" }
        sources.save(config.copy(platform = platform.trim(), enabledSections = null))
        scheduler.syncNow(sourceId, restart = true, full = true)
    }

    /** Switches the source to the next [SyncSpeed]; it applies from the next sync. */
    suspend fun cycleSpeed(sourceId: String) {
        val config = sources.get(sourceId) ?: return
        sources.save(config.withInterval(SyncSpeed.of(config.minRequestIntervalMs).next(allowTurbo = config is DriveConfig).intervalMs))
    }

    /** Stops that source's sync; games already read are kept. */
    fun stopSync(sourceId: String) = scheduler.cancel(sourceId)

    /**
     * Asks every source that has a site search and stores the hits in the local catalogue, where
     * the store's local search then finds them. Returns the number of games found, or null when
     * no source has a search. A failing site does not stop the others.
     */
    suspend fun searchRemote(query: String): Int? {
        val searchable = sources.all().filter { (it as? ScraperConfig)?.searchUrl != null }
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
