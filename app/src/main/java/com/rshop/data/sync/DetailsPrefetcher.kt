package com.rshop.data.sync

import com.rshop.data.metadata.GameMetadata
import com.rshop.di.ApplicationScope
import com.rshop.domain.model.Game
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the page of a game as soon as the player rests on its card, so the page is complete when
 * it opens. The background job does the same for every game in time; this one is for the game in
 * front of the player right now.
 */
@Singleton
class DetailsPrefetcher @Inject constructor(
    private val syncer: CatalogSyncer,
    private val metadata: GameMetadata,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val running = ConcurrentHashMap.newKeySet<String>()
    /** Pages that failed: not asked again until the app restarts. */
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val slots = Semaphore(MAX_PARALLEL)

    fun request(game: Game) {
        val read = game.detailsSyncedAt
        val stale = read == null || Duration.between(read, clock.instant()) >= FRESH_FOR
        if (game.id in failed || !running.add(game.id)) return
        scope.launch(Dispatchers.IO) {
            try {
                slots.withPermit {
                    if (stale) syncer.refreshDetails(game.id)
                    // Whatever the source's page lacks (screenshots, a description) comes from outside.
                    metadata.resolveNow(game.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Prefetch of %s failed", game.id)
                failed += game.id
            } finally {
                running -= game.id
            }
        }
    }

    private companion object {
        const val MAX_PARALLEL = 2
        val FRESH_FOR: Duration = Duration.ofHours(24)
    }
}
