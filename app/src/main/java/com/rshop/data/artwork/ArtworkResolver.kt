package com.rshop.data.artwork

import com.rshop.data.database.dao.ArtworkCandidate
import com.rshop.data.database.dao.GameDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fills game covers from SteamGridDB: search by cleaned title, then the best-voted portrait
 * grid. Each game is asked once (`artwork_checked_at`); requests are spaced out to stay polite.
 */
@Singleton
class ArtworkResolver @Inject constructor(
    private val gameDao: GameDao,
    private val settings: ArtworkSettings,
    private val okHttpClient: OkHttpClient,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Overridable in tests. */
    internal var clientFactory: (String) -> SteamGridDbClient = { key -> SteamGridDbClient(okHttpClient, key) }

    val pendingCount: Flow<Int> = gameDao.observePendingArtworkCount()

    /**
     * Resolves up to [maxGames] games still without an answer. Returns how many were handled;
     * 0 when there is nothing to do or no usable key. Network errors propagate (retry later).
     */
    suspend fun resolvePending(maxGames: Int): Int = mutex.withLock {
        val client = client() ?: return 0
        val games = gameDao.pendingArtwork(maxGames)
        for (game in games) resolve(client, game)
        games.size
    }

    /** Right away for one game (its page was opened); silently does nothing without a key. */
    suspend fun resolveNow(gameId: String) {
        val game = gameDao.pendingArtwork(gameId) ?: return
        mutex.withLock {
            val client = client() ?: return
            try {
                resolve(client, game)
            } catch (e: ArtworkException.InvalidKey) {
                // Already recorded.
            } catch (e: java.io.IOException) {
                Timber.w(e, "Cover lookup failed for %s", gameId)
            }
        }
    }

    /** After a key change: games left without a cover are asked again. */
    suspend fun retryMissing() = gameDao.retryMissingArtwork()

    /** At startup: when the matching rules changed, every cover is looked up again. */
    suspend fun redoIfMatcherChanged() {
        if (settings.consumeMatcherUpgrade()) gameDao.resetArtwork()
    }

    private suspend fun client(): SteamGridDbClient? {
        val key = settings.usableApiKey() ?: return null
        return clientFactory(key)
    }

    private suspend fun resolve(client: SteamGridDbClient, game: ArtworkCandidate) {
        try {
            val term = ArtworkTitle.searchTerm(game.title)
            val match = ArtworkTitle.pick(throttled { client.search(term) }, term)
            val cover = match?.let { throttled { client.cover(it.id) } }
            gameDao.setArtwork(game.id, cover, clock.millis())
            Timber.d("Cover for '%s' (%s): %s", game.title, term, cover ?: "none")
        } catch (e: ArtworkException.InvalidKey) {
            settings.markInvalidKey()
            throw e
        }
    }

    private var lastRequestAt = 0L

    private suspend fun <T> throttled(block: suspend () -> T): T {
        val wait = lastRequestAt + REQUEST_INTERVAL_MS - clock.millis()
        if (wait > 0) delay(wait.milliseconds)
        lastRequestAt = clock.millis()
        return block()
    }

    private companion object {
        const val REQUEST_INTERVAL_MS = 250L
    }
}
