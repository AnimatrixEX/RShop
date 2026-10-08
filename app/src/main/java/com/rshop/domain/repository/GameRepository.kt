package com.rshop.domain.repository

import androidx.paging.PagingData
import com.rshop.domain.model.CatalogFilter
import com.rshop.domain.model.Game
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface GameRepository {
    fun observeFeatured(): Flow<Game?>
    fun observeRecentlyAdded(limit: Int): Flow<List<Game>>
    fun observeRecentlyUpdated(limit: Int): Flow<List<Game>>
    fun observePopular(limit: Int): Flow<List<Game>>
    fun observeGenres(): Flow<List<String>>
    fun observePlatforms(): Flow<List<String>>
    fun observeCatalog(filter: CatalogFilter): Flow<List<Game>>

    /** Lazily loaded catalogue for large sources (the store grid). */
    fun pagedCatalog(filter: CatalogFilter): Flow<PagingData<Game>>
    fun observeCatalogCount(filter: CatalogFilter): Flow<Int>

    fun observeGame(id: String): Flow<Game?>
    suspend fun getGame(id: String): Game?

    fun observeFavorites(): Flow<List<Game>>
    fun observeIsFavorite(id: String): Flow<Boolean>
    suspend fun setFavorite(id: String, favorite: Boolean)

    /** Games whose page the user opened, most recent first. */
    fun observeRecentlyViewed(limit: Int): Flow<List<Game>>
    suspend fun recordView(id: String)

    suspend fun isCatalogEmpty(): Boolean

    /** Inserts or replaces catalogue entries as given (demo data, tests); user data is preserved. */
    suspend fun saveGames(games: List<Game>)

    /**
     * Merges entries read from listing pages: fields a listing does not carry (description,
     * files, hash…) are kept from earlier syncs, and the first-seen date becomes the added date.
     */
    suspend fun saveListing(games: List<Game>, syncedAt: Instant)

    /** Merges a game page read from the source and marks its details as fresh. */
    suspend fun saveDetails(game: Game)

    /** Deletes games of [sourceId] that a complete sync started at [before] did not see. */
    suspend fun deleteStaleGames(sourceId: String, before: Instant): Int

    /** Deletes every game of [sourceId] (a removed source). */
    suspend fun deleteGamesFrom(sourceId: String): Int

    /** Deletes games of any source not in [sourceIds] (demo catalogue, removed sites). */
    suspend fun deleteGamesNotFrom(sourceIds: List<String>): Int

    /** Ids of every game already stored for [sourceId] (incremental syncs skip them). */
    suspend fun knownGameIds(sourceId: String): Set<String>

    /** Ids of up to [limit] games whose download counter was never looked up (see [saveDownloadCount]). */
    suspend fun gamesWithoutStats(limit: Int): List<String>

    /** Stores the counter read from a game page; [count] null: the page shows none. */
    suspend fun saveDownloadCount(id: String, count: Long?)

    /** Number of games per source id. */
    fun observeCountsBySource(): Flow<Map<String, Int>>
}
