package com.rshop.data.backup

import android.content.Context
import com.rshop.data.repository.GameListRepository
import com.rshop.domain.repository.GameRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Favorites and list entries of a restored backup whose game is not in the catalogue yet (the
 * sources are read again after a restore). They wait here and are applied after each sync, for
 * a while: a game that never comes back is forgotten.
 */
@Singleton
class PendingRestore @Inject constructor(
    @ApplicationContext context: Context,
    private val games: GameRepository,
    private val lists: GameListRepository,
    private val clock: Clock,
) {
    @Serializable
    private data class PendingList(val name: String, val games: List<String>)

    @Serializable
    private data class Data(val createdAt: Long, val favorites: List<String> = emptyList(), val lists: List<PendingList> = emptyList())

    private val file = File(context.filesDir, "backup/pending.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** Adds to what already waits. [listEntries]: list name → game ids. */
    suspend fun add(favorites: List<String>, listEntries: Map<String, List<String>>) = lock.withLock {
        val current = read()
        val merged = Data(
            createdAt = clock.millis(),
            favorites = (current?.favorites.orEmpty() + favorites).distinct(),
            lists = (current?.lists.orEmpty() + listEntries.map { (name, ids) -> PendingList(name, ids) })
                .groupBy { it.name }
                .map { (name, parts) -> PendingList(name, parts.flatMap { it.games }.distinct()) },
        )
        write(merged.takeIf { it.favorites.isNotEmpty() || it.lists.any { l -> l.games.isNotEmpty() } })
    }

    /** Applies what the catalogue can resolve now; returns how many entries are still waiting. */
    suspend fun apply(): Int = lock.withLock {
        val data = read() ?: return@withLock 0
        if (clock.millis() - data.createdAt > MAX_AGE_MS) {
            write(null)
            return@withLock 0
        }
        val wanted = (data.favorites + data.lists.flatMap { it.games }).distinct()
        val present = games.existingIds(wanted)
        data.favorites.filter { it in present }.forEach { games.setFavorite(it, true) }
        val remainingLists = data.lists.map { list ->
            val here = list.games.filter { it in present }
            if (here.isNotEmpty()) {
                val id = lists.idOfOrCreate(list.name)
                if (id != null) here.forEach { lists.setMember(id, it, true) }
            }
            PendingList(list.name, list.games.filter { it !in present })
        }
        val remaining = Data(data.createdAt, data.favorites.filter { it !in present }, remainingLists.filter { it.games.isNotEmpty() })
        write(remaining.takeIf { it.favorites.isNotEmpty() || it.lists.isNotEmpty() })
        remaining.favorites.size + remaining.lists.sumOf { it.games.size }
    }

    private suspend fun read(): Data? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString(Data.serializer(), file.readText())
        } catch (e: Exception) {
            Timber.w(e, "Pending restore unreadable, dropped")
            file.delete()
            null
        }
    }

    private suspend fun write(data: Data?) = withContext(Dispatchers.IO) {
        try {
            if (data == null) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(Data.serializer(), data))
            }
        } catch (e: IOException) {
            Timber.w(e, "Cannot save pending restore")
        }
    }

    private companion object {
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(14)
    }
}
