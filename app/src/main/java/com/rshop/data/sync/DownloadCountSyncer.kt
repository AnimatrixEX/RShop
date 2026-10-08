package com.rshop.data.sync

import com.rshop.data.source.SourceRepository
import com.rshop.domain.repository.GameRepository
import com.rshop.scraper.ScraperException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the download counter of games whose listing does not show it. The counter is on the game
 * page, which a sync does not open; this opens the pages of games without a counter, a few at a
 * time (the rate limit of the source still spaces the requests), and stores what they show so
 * "popular" is right without the user opening every game.
 */
@Singleton
class DownloadCountSyncer @Inject constructor(
    private val sources: SourceRepository,
    private val games: GameRepository,
) {

    /** Looks up the next [limit] games; returns how many were looked up (fewer than [limit]: none left). */
    suspend fun refreshBatch(limit: Int): Int {
        val ids = games.gamesWithoutStats(limit)
        if (ids.isEmpty()) return 0
        val permits = Semaphore(CONCURRENCY)
        val bySource = ids.groupBy(::sourceIdOf)
        coroutineScope {
            bySource.flatMap { (sourceId, sourceGames) ->
                val config = sources.get(sourceId)
                val source = config?.let(sources::createSource)
                sourceGames.map { id ->
                    async {
                        permits.withPermit {
                            // A game of no configured source (demo catalogue) has no page to read.
                            val count = source?.let { lookUp(it, id.removePrefix("$sourceId:")) }
                            games.saveDownloadCount(id, count)
                        }
                    }
                }
            }.awaitAll()
        }
        return ids.size
    }

    /** Network trouble and a busy site propagate (the work is retried); a page that cannot be read just has no counter. */
    private suspend fun lookUp(source: com.rshop.scraper.GameSource, path: String): Long? = try {
        source.getDownloadCount(path)
    } catch (e: ScraperException.Network) {
        throw e
    } catch (e: ScraperException.Busy) {
        throw e
    } catch (e: ScraperException) {
        Timber.d(e, "No download counter for %s", path)
        null
    }

    private companion object {
        const val CONCURRENCY = 3
    }
}
