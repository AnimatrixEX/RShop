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
import com.rshop.domain.genre.TagCodec
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

    override fun observeGenres(): Flow<List<String>> = gameDao.observeTagGroups().map { groups ->
        val counts = HashMap<String, Int>()
        groups.forEach { group -> TagCodec.decode(group.tags).forEach { counts.merge(it, group.games, Int::plus) } }
        // Most common first, so the chips that matter come first.
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }.take(MAX_GENRE_CHIPS)
    }

    override fun observePlatforms(): Flow<List<String>> = gameDao.observePlatforms()

    override fun observeCatalog(filter: CatalogFilter): Flow<List<Game>> = gameDao.observeCatalog(
        ftsQuery = FtsQuery.from(filter.query),
        tag = filter.genre?.let(TagCodec::pattern),
        platform = filter.platform,
        sourceId = filter.sourceId,
        sort = filter.sort.toSql(),
    ).mapGames()

    override fun pagedCatalog(filter: CatalogFilter): Flow<PagingData<Game>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE / 2, enablePlaceholders = false)) {
            gameDao.pagingCatalog(FtsQuery.from(filter.query), filter.genre?.let(TagCodec::pattern), filter.platform, filter.sourceId, filter.sort.toSql())
        }.flow.map { data -> data.map { it.toDomain() } }

    override fun observeCatalogCount(filter: CatalogFilter): Flow<Int> =
        gameDao.observeCatalogCount(FtsQuery.from(filter.query), filter.genre?.let(TagCodec::pattern), filter.platform, filter.sourceId)

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

    override suspend fun deleteGamesOfPlatforms(sourceId: String, platforms: List<String>): Int =
        if (platforms.isEmpty()) 0 else gameDao.deletePlatforms(sourceId, platforms)

    override suspend fun deleteGamesNotFrom(sourceIds: List<String>): Int = gameDao.deleteOtherSources(sourceIds)

    override suspend fun knownGameIds(sourceId: String): Set<String> = gameDao.idsOfSource(sourceId).toHashSet()

    override suspend fun gamesWithoutStats(limit: Int): List<String> = gameDao.pendingStats(limit)

    override suspend fun saveDownloadCount(id: String, count: Long?) = gameDao.setStats(id, count, clock.millis())

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
        const val MAX_GENRE_CHIPS = 40
        val HISTORY_RETENTION: Duration = Duration.ofDays(180)
    }
}
