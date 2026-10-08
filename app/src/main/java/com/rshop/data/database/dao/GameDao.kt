package com.rshop.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rshop.data.database.entity.GameEntity
import com.rshop.data.repository.CatalogMerge
import com.rshop.data.database.entity.GameWithScreenshots
import com.rshop.data.database.entity.ScreenshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    @Query("SELECT * FROM games ORDER BY popularity DESC, title COLLATE NOCASE LIMIT 1")
    fun observeFeatured(): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE added_at IS NOT NULL ORDER BY added_at DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE updated_at IS NOT NULL ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecentlyUpdated(limit: Int): Flow<List<GameEntity>>

    @Query("SELECT * FROM games ORDER BY popularity DESC, title COLLATE NOCASE LIMIT :limit")
    fun observePopular(limit: Int): Flow<List<GameEntity>>

    /** Every distinct encoded tag list with its number of games (see TagCodec); split by the repository. */
    @Query("SELECT tags, COUNT(*) AS games FROM games WHERE tags IS NOT NULL GROUP BY tags")
    fun observeTagGroups(): Flow<List<TagGroup>>

    @Query("SELECT DISTINCT platform FROM games WHERE platform IS NOT NULL AND platform != '' ORDER BY platform COLLATE NOCASE")
    fun observePlatforms(): Flow<List<String>>

    @Transaction
    @Query("SELECT * FROM games WHERE id = :id")
    fun observeGame(id: String): Flow<GameWithScreenshots?>

    /**
     * Catalogue listing. [ftsQuery] must already be a sanitized FTS expression (see FtsQuery);
     * [sort] is one of the [CatalogSort] constants.
     */
    @Query(
        """
        SELECT * FROM games
        WHERE (:ftsQuery IS NULL OR rowid IN (SELECT rowid FROM games_fts WHERE games_fts MATCH :ftsQuery))
          AND (:tag IS NULL OR tags LIKE :tag)
          AND (:platform IS NULL OR platform = :platform)
          AND (:sourceId IS NULL OR source_id = :sourceId)
        ORDER BY
          CASE WHEN :sort = 'title' THEN title END COLLATE NOCASE ASC,
          CASE WHEN :sort = 'popular' THEN popularity END DESC,
          CASE WHEN :sort = 'added' THEN added_at END DESC,
          CASE WHEN :sort = 'updated' THEN updated_at END DESC,
          CASE WHEN :sort = 'size' THEN size_bytes END DESC,
          title COLLATE NOCASE ASC
        """,
    )
    fun observeCatalog(ftsQuery: String?, tag: String?, platform: String?, sourceId: String?, sort: String): Flow<List<GameEntity>>

    @Query(
        """
        SELECT * FROM games
        WHERE (:ftsQuery IS NULL OR rowid IN (SELECT rowid FROM games_fts WHERE games_fts MATCH :ftsQuery))
          AND (:tag IS NULL OR tags LIKE :tag)
          AND (:platform IS NULL OR platform = :platform)
          AND (:sourceId IS NULL OR source_id = :sourceId)
        ORDER BY
          CASE WHEN :sort = 'title' THEN title END COLLATE NOCASE ASC,
          CASE WHEN :sort = 'popular' THEN popularity END DESC,
          CASE WHEN :sort = 'added' THEN added_at END DESC,
          CASE WHEN :sort = 'updated' THEN updated_at END DESC,
          CASE WHEN :sort = 'size' THEN size_bytes END DESC,
          title COLLATE NOCASE ASC
        """,
    )
    fun pagingCatalog(ftsQuery: String?, tag: String?, platform: String?, sourceId: String?, sort: String): PagingSource<Int, GameEntity>

    @Query(
        """
        SELECT COUNT(*) FROM games
        WHERE (:ftsQuery IS NULL OR rowid IN (SELECT rowid FROM games_fts WHERE games_fts MATCH :ftsQuery))
          AND (:tag IS NULL OR tags LIKE :tag)
          AND (:platform IS NULL OR platform = :platform)
          AND (:sourceId IS NULL OR source_id = :sourceId)
        """,
    )
    fun observeCatalogCount(ftsQuery: String?, tag: String?, platform: String?, sourceId: String?): Flow<Int>

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int

    @Query("SELECT id FROM games WHERE source_id = :sourceId")
    suspend fun idsOfSource(sourceId: String): List<String>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: String): GameEntity?

    @Query("SELECT * FROM games WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<GameEntity>

    /** Removes games a completed sync of [sourceId] no longer saw. */
    @Query("DELETE FROM games WHERE source_id = :sourceId AND last_synced_at < :before")
    suspend fun deleteStale(sourceId: String, before: Long): Int

    @Query("DELETE FROM games WHERE source_id = :sourceId")
    suspend fun deleteSource(sourceId: String): Int

    /** Removes games of sources that are no longer configured (demo catalogue, removed sites). */
    @Query("DELETE FROM games WHERE source_id NOT IN (:sourceIds)")
    suspend fun deleteOtherSources(sourceIds: List<String>): Int

    @Query("SELECT source_id, COUNT(*) AS games FROM games GROUP BY source_id")
    fun observeCountsBySource(): Flow<List<SourceGameCount>>

    @Upsert
    suspend fun upsertGames(games: List<GameEntity>)

    @Query("DELETE FROM screenshots WHERE game_id IN (:gameIds)")
    suspend fun deleteScreenshots(gameIds: List<String>)

    @Insert
    suspend fun insertScreenshots(screenshots: List<ScreenshotEntity>)

    /**
     * Inserts or updates games without deleting rows first, so favorites and history pointing at
     * them (ON DELETE CASCADE) survive every sync. Screenshots are replaced as a whole.
     */
    @Transaction
    suspend fun upsertWithScreenshots(games: List<GameWithScreenshots>) {
        games.chunked(CHUNK_SIZE).forEach { chunk ->
            upsertGames(chunk.map { it.game })
            deleteScreenshots(chunk.map { it.game.id })
            insertScreenshots(chunk.flatMap { it.screenshots })
        }
    }

    /** Listing pages merged into existing rows (see CatalogMerge.listing). */
    @Transaction
    suspend fun mergeListing(games: List<GameEntity>, now: Long) {
        games.chunked(CHUNK_SIZE).forEach { chunk ->
            val existing = getByIds(chunk.map { it.id }).associateBy { it.id }
            upsertGames(chunk.map { CatalogMerge.listing(existing[it.id], it, now) })
        }
    }

    /** A game page merged into its row; screenshots are replaced when the page has some. */
    @Transaction
    suspend fun mergeDetails(game: GameWithScreenshots, now: Long) {
        upsertGames(listOf(CatalogMerge.details(get(game.game.id), game.game, now)))
        if (game.screenshots.isNotEmpty()) {
            deleteScreenshots(listOf(game.game.id))
            insertScreenshots(game.screenshots)
        }
    }

    /** Games SteamGridDB was never asked about: installed ones first, then the most visible. */
    @Query(
        """
        SELECT id, title FROM games WHERE artwork_checked_at IS NULL
        ORDER BY (id IN (SELECT game_id FROM installed_games)) DESC, popularity DESC, added_at DESC
        LIMIT :limit
        """,
    )
    suspend fun pendingArtwork(limit: Int): List<ArtworkCandidate>

    @Query("SELECT id, title FROM games WHERE id = :id AND artwork_checked_at IS NULL")
    suspend fun pendingArtwork(id: String): ArtworkCandidate?

    @Query("SELECT COUNT(*) FROM games WHERE artwork_checked_at IS NULL")
    fun observePendingArtworkCount(): Flow<Int>

    @Query("UPDATE games SET cover_url = :coverUrl, artwork_checked_at = :checkedAt WHERE id = :id")
    suspend fun setGameArtwork(id: String, coverUrl: String?, checkedAt: Long)

    @Query("UPDATE installed_games SET cover_url = :coverUrl WHERE game_id = :id")
    suspend fun setInstalledArtwork(id: String, coverUrl: String?)

    @Query("UPDATE downloads SET cover_url = :coverUrl WHERE game_id = :id")
    suspend fun setDownloadArtwork(id: String, coverUrl: String?)

    /** Covers copied into the library and the download queue follow the catalogue. */
    @Transaction
    suspend fun setArtwork(id: String, coverUrl: String?, checkedAt: Long) {
        setGameArtwork(id, coverUrl, checkedAt)
        setInstalledArtwork(id, coverUrl)
        setDownloadArtwork(id, coverUrl)
    }

    /** Games whose download counter was never looked up and whose page was never read, newest first. */
    @Query(
        """
        SELECT id FROM games
        WHERE download_count IS NULL AND stats_checked_at IS NULL AND details_synced_at IS NULL
        ORDER BY added_at DESC
        LIMIT :limit
        """,
    )
    suspend fun pendingStats(limit: Int): List<String>

    /** [count] null: the page shows no counter; the game is not asked about again. */
    @Query(
        """
        UPDATE games SET
          download_count = COALESCE(:count, download_count),
          popularity = CASE WHEN :count IS NULL THEN popularity ELSE MIN(:count, 2147483647) END,
          stats_checked_at = :checkedAt
        WHERE id = :id
        """,
    )
    suspend fun setStats(id: String, count: Long?, checkedAt: Long)

    @Query("UPDATE games SET artwork_checked_at = NULL")
    suspend fun resetArtwork()

    /** Asks SteamGridDB again for the games still without a cover (after a new API key). */
    @Query("UPDATE games SET artwork_checked_at = NULL WHERE cover_url IS NULL")
    suspend fun retryMissingArtwork()

    private companion object {
        /** Stays far below SQLite's bound-parameter limit for the IN clause. */
        const val CHUNK_SIZE = 500
    }
}

object CatalogSort {
    const val TITLE = "title"
    const val POPULAR = "popular"
    const val ADDED = "added"
    const val UPDATED = "updated"
    const val SIZE = "size"
}

data class TagGroup(val tags: String, val games: Int)

data class ArtworkCandidate(val id: String, val title: String)

data class SourceGameCount(
    @androidx.room.ColumnInfo(name = "source_id") val sourceId: String,
    val games: Int,
)
