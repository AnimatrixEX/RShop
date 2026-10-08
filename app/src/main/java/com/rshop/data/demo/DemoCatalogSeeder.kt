package com.rshop.data.demo

import android.content.Context
import com.rshop.domain.genre.GenreClassifier
import com.rshop.domain.model.Game
import com.rshop.domain.repository.GameRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.FileNotFoundException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills an empty database with fictional games so the UI can be exercised before a real source
 * is configured. The asset only ships in debug builds; release builds find nothing and skip.
 */
@Singleton
class DemoCatalogSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GameRepository,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun seedIfEmpty() {
        if (!repository.isCatalogEmpty()) return
        val text = withContext(Dispatchers.IO) {
            try {
                context.assets.open(ASSET).bufferedReader().use { it.readText() }
            } catch (_: FileNotFoundException) {
                null
            }
        } ?: return

        val games = try {
            parse(text, clock.instant())
        } catch (e: SerializationException) {
            Timber.e(e, "Invalid demo catalogue")
            return
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Invalid demo catalogue")
            return
        }
        repository.saveGames(games)
        Timber.i("Seeded %d demo games", games.size)
    }

    /** Dates are relative so the demo always has "recent" games, whenever it is installed. */
    internal fun parse(text: String, now: Instant): List<Game> =
        json.decodeFromString<List<DemoGame>>(text).map { demo ->
            Game(
                id = demo.id,
                title = demo.title,
                description = demo.description,
                coverUrl = null,
                screenshots = emptyList(),
                downloadUrl = null,
                version = demo.version,
                sizeBytes = demo.sizeMb * 1024 * 1024,
                platform = demo.platform,
                genre = demo.genre,
                tags = GenreClassifier.classify(demo.genre, demo.title, demo.description, demo.platform),
                sourceUrl = null,
                addedAt = now - Duration.ofDays(demo.addedDaysAgo),
                updatedAt = now - Duration.ofDays(demo.updatedDaysAgo),
                popularity = demo.popularity,
            )
        }

    @Serializable
    private data class DemoGame(
        val id: String,
        val title: String,
        val description: String? = null,
        val platform: String? = null,
        val genre: String? = null,
        val version: String? = null,
        val sizeMb: Long,
        val addedDaysAgo: Long,
        val updatedDaysAgo: Long,
        val popularity: Int = 0,
    )

    private companion object {
        const val ASSET = "demo_catalog.json"
    }
}
