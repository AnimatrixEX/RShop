package com.rshop.data.metadata

import com.rshop.data.artwork.ArtworkSettings
import com.rshop.data.artwork.LibretroThumbnails
import com.rshop.data.database.dao.GameDao
import com.rshop.data.database.dao.DescriptionResult
import com.rshop.data.database.dao.MetadataCandidate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screenshots and a description for games, from places that are not the catalogue source: the
 * Libretro thumbnails (a screenshot and the title screen, matched against a list kept on the
 * device, so no request per game) and Wikipedia (the start of the game's article, credited). The
 * source's own data always wins: it replaces these as soon as the source has any.
 */
@Singleton
class GameMetadata @Inject constructor(
    private val gameDao: GameDao,
    private val settings: ArtworkSettings,
    private val libretro: LibretroThumbnails,
    private val wikipedia: WikipediaClient,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /**
     * Looks up the next games, [maxGames] at most. Returns how many were handled; fewer than asked
     * means nothing is left. Network trouble propagates (retried later).
     */
    suspend fun resolvePending(maxGames: Int): Int = mutex.withLock {
        var handled = 0
        if (settings.libretroEnabled()) {
            // A local match: a big batch, written in one transaction.
            val games = gameDao.pendingScreenshots(maxGames.coerceAtLeast(SCREENSHOT_BATCH))
            val found = ArrayList<Pair<String, List<String>>>(games.size)
            for (game in games) found += game.id to libretro.findScreenshots(game.platform, game.title)
            if (found.isNotEmpty()) gameDao.setExternalScreenshotsBatch(found, clock.millis())
            handled += games.size
        }
        if (settings.metadataEnabled()) {
            // Two requests per game: a short round, the worker comes back for more.
            val games = gameDao.pendingDescriptions(DESCRIPTION_BATCH)
            val found = ArrayList<DescriptionResult>(games.size)
            try {
                for (game in games) {
                    val description = wikipedia.describe(game.title, languages())
                    found += DescriptionResult(game.id, description?.text, description?.source)
                }
            } finally {
                // What was found before a network error is kept.
                if (found.isNotEmpty()) withContext(NonCancellable) { gameDao.setExternalDescriptionBatch(found, clock.millis()) }
            }
            handled += games.size
        }
        handled
    }

    /** Right away for one game (its page was opened or it has the focus); does nothing when done already. */
    suspend fun resolveNow(gameId: String) {
        val game = gameDao.metadataCandidate(gameId) ?: return
        if (!game.wantsScreenshots && !game.wantsDescription) return
        mutex.withLock {
            try {
                lookUp(game)
            } catch (e: java.io.IOException) {
                Timber.d(e, "Infos lookup failed for %s", gameId)
            }
        }
    }

    private suspend fun lookUp(game: MetadataCandidate) {
        val now = clock.millis()
        if (game.wantsScreenshots && settings.libretroEnabled()) {
            val shots = libretro.findScreenshots(game.platform, game.title)
            gameDao.setExternalScreenshots(game.id, shots, now)
        }
        if (game.wantsDescription && settings.metadataEnabled()) {
            val found = wikipedia.describe(game.title, languages())
            gameDao.setExternalDescription(game.id, found?.text, found?.source, now)
            Timber.d("Description for '%s': %s", game.title, found?.articleTitle ?: "none")
        }
    }

    /** The player's language first (when Wikipedia has it), English as the fallback. */
    private fun languages(): List<String> {
        val own = Locale.getDefault().language
        return if (own == "fr") listOf("fr", "en") else listOf("en")
    }

    private companion object {
        const val SCREENSHOT_BATCH = 400
        const val DESCRIPTION_BATCH = 20
    }
}
