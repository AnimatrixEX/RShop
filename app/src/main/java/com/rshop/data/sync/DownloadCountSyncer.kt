package com.rshop.data.sync

import com.rshop.domain.repository.GameRepository
import com.rshop.scraper.ScraperException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the game pages a sync does not open: description, screenshots, files, size, version and
 * the download counter. Without this they would only appear when the player opens a game; this
 * opens the pages of the favorites and of the most popular games not read yet, a few at a time
 * (the rate limit of the source still spaces the requests). The other games are read when the
 * player rests on their card or opens them, which keeps the traffic small on a huge catalogue.
 */
@Singleton
class DownloadCountSyncer @Inject constructor(
    private val syncer: CatalogSyncer,
    private val games: GameRepository,
) {

    /** Reads the next [limit] game pages; returns how many were handled (fewer than [limit]: none left). */
    suspend fun refreshBatch(limit: Int): Int {
        val ids = games.gamesWithoutStats(limit)
        if (ids.isEmpty()) return 0
        val permits = Semaphore(CONCURRENCY)
        coroutineScope {
            ids.map { id -> async { permits.withPermit { read(id) } } }.awaitAll()
        }
        return ids.size
    }

    /**
     * Network trouble and a busy site propagate (the work is retried). A page that cannot be read,
     * or a game of no configured source (the demo catalogue), is marked as asked: it is not tried
     * again, the game page reads it when the player opens it.
     */
    private suspend fun read(id: String) {
        try {
            if (syncer.refreshDetails(id) == null) games.saveDownloadCount(id, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScraperException.Network) {
            throw e
        } catch (e: ScraperException.Busy) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Game page of %s could not be read", id)
            games.saveDownloadCount(id, null)
        }
    }

    private companion object {
        const val CONCURRENCY = 3
    }
}
