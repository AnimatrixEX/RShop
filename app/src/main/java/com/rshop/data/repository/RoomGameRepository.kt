package com.rshop.data.repository

import com.rshop.data.database.FtsQuery
import com.rshop.data.database.dao.CatalogSort
import com.rshop.data.database.dao.FavoriteDao
import com.rshop.data.database.dao.GameDao
import com.rshop.data.database.dao.HistoryDao
import com.rshop.data.database.entity.FavoriteEntity
import com.rshop.data.database.entity.GameEntity
import com.rshop.data.database.entity.HistoryEntity
import com.rshop.data.database.toDomain
import com.rshop.data.database.toEntity
import com.rshop.domain.model.CatalogFilter
import com.rshop.domain.model.Game
import com.rshop.domain.model.SortOrder
import com.rshop.domain.repository.GameRepository
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomGameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val clock: Clock,
) : GameRepository {

    override fun observeFeatured(): Flow<Game?> = gameDao.observeFeatured().map { it?.toDomain() }

    override fun observeRecentlyAdded(limit: Int): Flow<List<Game>> = gameDao.observeRecentlyAdded(limit).mapGames()

    override fun observeRecentlyUpdated(limit: Int): Flow<List<Game>> = gameDao.observeRecentlyUpdated(limit).mapGames()

    override fun observePopular(limit: Int): Flow<List<Game>> = gameDao.observePopular(limit).mapGames()

    override fun observeGenres(): Flow<List<String>> = gameDao.observeGenres()

    override fun observePlatforms(): Flow<List<String>> = gameDao.observePlatforms()

    override fun observeCatalog(filter: CatalogFilter): Flow<List<Game>> = gameDao.observeCatalog(
        ftsQuery = FtsQuery.from(filter.query),
        genre = filter.genre,
        platform = filter.platform,
        sourceId = filter.sourceId,
        sort = filter.sort.toSql(),
    ).mapGames()

    override fun pagedCatalog(filter: CatalogFilter): Flow<PagingData<Game>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE / 2, enablePlaceholders = false)) {
            gameDao.pagingCatalog(FtsQuery.from(filter.query), filter.genre, filter.platform, filter.sourceId, filter.sort.toSql())
        }.flow.map { data -> data.map { it.toDomain() } }

    override fun observeCatalogCount(filter: CatalogFilter): Flow<Int> =
        gameDao.observeCatalogCount(FtsQuery.from(filter.query), filter.genre, filter.platform, filter.sourceId)

    override fun observeGame(id: String): Flow<Game?> = gameDao.observeGame(id).map { it?.toDomain() }

    override suspend fun getGame(id: String): Game? = gameDao.observeGame(id).first()?.toDomain()

    override fun observeFavorites(): Flow<List<Game>> = favoriteDao.observeFavoriteGames().mapGames()

    override fun observeIsFavorite(id: String): Flow<Boolean> = favoriteDao.observeIsFavorite(id)

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) {
            favoriteDao.insert(FavoriteEntity(gameId = id, addedAt = clock.millis()))
        } else {
            favoriteDao.delete(id)
        }
    }

    override fun observeRecentlyViewed(limit: Int): Flow<List<Game>> =
        historyDao.observeRecentGames(HISTORY_VIEWED, limit).mapGames()

    override suspend fun recordView(id: String) {
        val now = clock.millis()
        historyDao.insert(HistoryEntity(gameId = id, type = HISTORY_VIEWED, timestamp = now))
        historyDao.deleteOlderThan(now - HISTORY_RETENTION.toMillis())
    }

    override suspend fun isCatalogEmpty(): Boolean = gameDao.count() == 0

    override suspend fun saveGames(games: List<Game>) {
        val now = clock.millis()
        gameDao.upsertWithScreenshots(games.map { it.toEntity(syncedAt = now) })
    }

    override suspend fun saveListing(games: List<Game>, syncedAt: Instant) {
        val now = syncedAt.toEpochMilli()
        gameDao.mergeListing(games.map { it.toEntity(syncedAt = now).game }, now)
    }

    override suspend fun saveDetails(game: Game) {
        val now = clock.millis()
        gameDao.mergeDetails(game.toEntity(syncedAt = now), now)
    }

    override suspend fun deleteStaleGames(sourceId: String, before: Instant): Int =
        gameDao.deleteStale(sourceId, before.toEpochMilli())

    override suspend fun deleteGamesFrom(sourceId: String): Int = gameDao.deleteSource(sourceId)

    override suspend fun deleteGamesNotFrom(sourceIds: List<String>): Int = gameDao.deleteOtherSources(sourceIds)

    override fun observeCountsBySource(): Flow<Map<String, Int>> =
        gameDao.observeCountsBySource().map { counts -> counts.associate { it.sourceId to it.games } }

    private fun SortOrder.toSql() = when (this) {
        SortOrder.Title -> CatalogSort.TITLE
        SortOrder.RecentlyAdded -> CatalogSort.ADDED
        SortOrder.RecentlyUpdated -> CatalogSort.UPDATED
        SortOrder.Size -> CatalogSort.SIZE
        SortOrder.Popular -> CatalogSort.POPULAR
    }

    private fun Flow<List<GameEntity>>.mapGames(): Flow<List<Game>> = map { list -> list.map { it.toDomain() } }

    private companion object {
        const val HISTORY_VIEWED = "viewed"
        const val PAGE_SIZE = 48
        val HISTORY_RETENTION: Duration = Duration.ofDays(180)
    }
}
