package com.rshop.data.sync

import com.rshop.data.source.SourceRepository
import com.rshop.domain.model.Game
import com.rshop.domain.repository.GameRepository
import com.rshop.scraper.ScraperException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

data class SyncProgress(val pages: Int, val games: Int, val section: String?)

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
) {

    suspend fun sync(sourceId: String, onProgress: suspend (SyncProgress) -> Unit = {}): Int {
        val config = sources.get(sourceId) ?: throw NoSourceConfiguredException()
        val source = sources.createSource(config)
        val start = clock.instant()

        val seen = HashSet<String>()
        var pages = 0
        var pagesWithoutNewGames = 0
        source.crawl().takeWhile { page ->
            pages++
            val fresh = page.games.filter { seen.add(it.id) }
            if (fresh.isNotEmpty()) games.saveListing(fresh.map { it.toDomain(config.id) }, start)
            onProgress(SyncProgress(pages, seen.size, page.section))
            // Guard against sites that serve the last page again for any page number.
            pagesWithoutNewGames = if (fresh.isEmpty()) pagesWithoutNewGames + 1 else 0
            pagesWithoutNewGames < MAX_PAGES_WITHOUT_NEW_GAMES
        }.collect()

        if (seen.isEmpty()) {
            throw ScraperException.StructureChanged(config.baseUrl, "the sync found no game")
        }
        val stale = games.deleteStaleGames(config.id, start)
        Timber.i("Sync of %s done: %d games in %d pages, %d removed", config.id, seen.size, pages, stale)
        return seen.size
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
