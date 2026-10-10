package com.rshop.data.sync

import com.rshop.data.backup.PendingRestore
import com.rshop.data.source.SourceRepository
import com.rshop.domain.model.Game
import com.rshop.domain.repository.GameRepository
import com.rshop.scraper.ScraperException
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

data class SyncProgress(val pages: Int, val games: Int, val section: String?)

/** [games]: games known for the source after the run; [fullScan]: the run went through the whole catalogue. */
data class SyncOutcome(val games: Int, val fullScan: Boolean)

class NoSourceConfiguredException : IllegalStateException("No catalogue source configured")

/** Source part of a game id ("<sourceId>:<path>"). */
fun sourceIdOf(gameId: String): String = gameId.substringBefore(':', missingDelimiterValue = "")

/**
 * Reads the whole catalogue of one source into the database, page by page, so the UI fills
 * progressively. Games that disappeared from that source are removed only after a complete run:
 * an interrupted sync never deletes anything, and other sources are never touched.
 */
@Singleton
class CatalogSyncer @Inject constructor(
    private val sources: SourceRepository,
    private val games: GameRepository,
    private val clock: Clock,
    private val status: SyncStatusStore,
    private val pendingRestore: PendingRestore,
) {

    /**
     * Once a source was scanned completely, later runs are incremental: each listing is read from
     * its first page until a page holds only games already known, so the same games are not
     * scanned again. [full] forces a complete rescan (which also removes games that left the site).
     */
    suspend fun sync(sourceId: String, full: Boolean = false, onProgress: suspend (SyncProgress) -> Unit = {}): SyncOutcome {
        val config = sources.get(sourceId) ?: throw NoSourceConfiguredException()
        val source = sources.createSource(config)
        val start = clock.instant()

        val incremental = !full && status.current(sourceId)?.lastFullScanAt != null
        val known = if (incremental) games.knownGameIds(config.id) else emptySet()
        val prefix = "${config.id}:"
        val seen = HashSet<String>()
        var pages = 0
        var pagesWithoutNewGames = 0
        val crawl = if (incremental) source.crawl { scrapedId -> (prefix + scrapedId) in known } else source.crawl()
        // Saving a page overlaps with fetching the next ones.
        crawl.buffer(2).takeWhile { page ->
            pages++
            val fresh = page.games.filter { seen.add(it.id) }
            if (fresh.isNotEmpty()) games.saveListing(fresh.map { it.toDomain(config.id) }, start)
            onProgress(SyncProgress(pages, seen.size, page.section))
            // Guard against sites that serve the last page again for any page number.
            pagesWithoutNewGames = if (fresh.isEmpty()) pagesWithoutNewGames + 1 else 0
            incremental || pagesWithoutNewGames < MAX_PAGES_WITHOUT_NEW_GAMES
        }.collect()

        if (seen.isEmpty()) {
            throw ScraperException.StructureChanged(config.location, "the sync found no game")
        }
        // Favorites and list entries of a restored backup whose games have just arrived.
        runCatching { pendingRestore.apply() }.onFailure { Timber.w(it, "Pending restore failed") }
        if (incremental) {
            val total = known.size + seen.count { (prefix + it) !in known }
            Timber.i("Incremental sync of %s: %d new games, %d pages read", config.id, total - known.size, pages)
            return SyncOutcome(total, fullScan = false)
        }
        // A scan cut short by the request budget has not reached everything: nothing may be
        // deleted from it and it does not count as complete.
        val complete = !source.crawlTruncated
        val stale = if (complete) games.deleteStaleGames(config.id, start) else 0
        // Warning level: release builds keep it, so a sync can be checked on a device.
        Timber.w("Sync of %s done: %d games in %d pages, %d removed, complete=%s", config.id, seen.size, pages, stale, complete)
        return SyncOutcome(seen.size, fullScan = complete)
    }

    /**
     * Reads one game page (description, files, hash). Returns null when the game belongs to no
     * configured source (e.g. demo games).
     */
    suspend fun refreshDetails(gameId: String): Game? {
        val config = sources.get(sourceIdOf(gameId)) ?: return null
        val details = sources.createSource(config).getGameDetails(gameId.removePrefix("${config.id}:"))
        games.saveDetails(details.toDomain(config.id))
        return games.getGame(gameId)
    }

    private companion object {
        const val MAX_PAGES_WITHOUT_NEW_GAMES = 3
    }
}
