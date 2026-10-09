package com.rshop.data.artwork

import com.rshop.data.database.dao.ArtworkCandidate
import com.rshop.data.database.dao.GameDao
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val libretro: LibretroThumbnails,
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
        val client = client()
        val useLibretro = settings.libretroEnabled()
        // Neither SteamGridDB (no key) nor Libretro (switched off): nothing to look up.
        if (client == null && !useLibretro) return 0
        val games = gameDao.pendingArtwork(maxGames)
        // Several games at once: the answers of the API arrive while the next requests go out.
        // Requests are still spaced by [throttled], so the rate stays bounded.
        val slots = Semaphore(PARALLEL_GAMES)
        coroutineScope {
            games.map { game -> async { slots.withPermit { resolve(client, game, useLibretro) } } }.awaitAll()
        }
        games.size
    }

    /** Right away for one game (its page was opened); silently does nothing without a key. */
    suspend fun resolveNow(gameId: String) {
        val game = gameDao.pendingArtwork(gameId) ?: return
        mutex.withLock {
            val client = client()
            val useLibretro = settings.libretroEnabled()
            if (client == null && !useLibretro) return
            try {
                resolve(client, game, useLibretro)
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
        if (settings.consumeLibretroUpgrade()) gameDao.retryMissingArtwork()
    }

    private suspend fun client(): SteamGridDbClient? {
        val key = settings.usableApiKey() ?: return null
        return clientFactory(key)
    }

    /** SteamGridDB first when there is a key, then the Libretro thumbnails for what it did not have. */
    private suspend fun resolve(client: SteamGridDbClient?, game: ArtworkCandidate, useLibretro: Boolean) {
        var cover: String? = null
        if (client != null) {
            try {
                val term = ArtworkTitle.searchTerm(game.title)
                val match = ArtworkTitle.pick(throttled { client.search(term) }, term)
                cover = match?.let { throttled { client.cover(it.id) } }
            } catch (e: ArtworkException.InvalidKey) {
                settings.markInvalidKey()
                // Libretro does not need the key: carry on with it for this game.
                if (!useLibretro) throw e
            }
        }
        if (cover == null && useLibretro) cover = libretro.find(game.platform, game.title)
        gameDao.setArtwork(game.id, cover, clock.millis())
        Timber.d("Cover for '%s' (%s): %s", game.title, game.platform, cover ?: "none")
    }

    private val throttleLock = Mutex()
    private var lastRequestAt = 0L

    /** Spaces request starts by [REQUEST_INTERVAL_MS] across all games; a 429 answer waits and retries. */
    private suspend fun <T> throttled(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            throttleLock.withLock {
                val wait = lastRequestAt + REQUEST_INTERVAL_MS - clock.millis()
                if (wait > 0) delay(wait.milliseconds)
                lastRequestAt = clock.millis()
            }
            try {
                return block()
            } catch (e: ArtworkException.Http) {
                if (e.code != 429 || ++attempt > MAX_RATE_RETRIES) throw e
                Timber.w("SteamGridDB asked to slow down (attempt %d)", attempt)
                delay((RATE_BACKOFF_MS * attempt).milliseconds)
            }
        }
    }

    private companion object {
        /** Gap between two request starts, shared by every game being resolved. */
        const val REQUEST_INTERVAL_MS = 110L
        const val PARALLEL_GAMES = 4
        const val MAX_RATE_RETRIES = 3
        const val RATE_BACKOFF_MS = 2_000L
    }
}
