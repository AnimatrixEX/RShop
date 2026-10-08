package com.rshop.data.repository

import com.rshop.data.database.dao.GameListDao
import com.rshop.data.database.entity.GameListEntity
import com.rshop.data.database.entity.GameListEntryEntity
import com.rshop.data.database.toDomain
import com.rshop.domain.model.Game
import com.rshop.domain.model.GameList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** The user's own lists of games ("À finir", "Co-op"…), next to the built-in favorites. */
@Singleton
class GameListRepository @Inject constructor(
    private val dao: GameListDao,
    private val clock: Clock,
) {
    fun observeLists(): Flow<List<GameList>> = dao.observeLists().map { lists -> lists.map { GameList(it.id, it.name, it.games) } }

    fun observeGames(listId: Long): Flow<List<Game>> = dao.observeGames(listId).map { games -> games.map { it.toDomain() } }

    fun observeListIdsOf(gameId: String): Flow<Set<Long>> = dao.observeListIdsOf(gameId).map { it.toSet() }

    /** Creates a list and returns its id, or null when [name] is blank. */
    suspend fun create(name: String): Long? {
        val clean = name.trim().take(MAX_NAME).takeIf { it.isNotEmpty() } ?: return null
        return dao.insertList(GameListEntity(name = clean, createdAt = clock.millis()))
    }

    suspend fun rename(listId: Long, name: String) {
        name.trim().take(MAX_NAME).takeIf { it.isNotEmpty() }?.let { dao.rename(listId, it) }
    }

    suspend fun delete(listId: Long) = dao.delete(listId)

    suspend fun setMember(listId: Long, gameId: String, member: Boolean) {
        if (member) dao.addEntry(GameListEntryEntity(listId, gameId, clock.millis())) else dao.removeEntry(listId, gameId)
    }

    private companion object {
        const val MAX_NAME = 40
    }
}
