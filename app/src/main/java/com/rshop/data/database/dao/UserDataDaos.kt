package com.rshop.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.rshop.data.database.entity.DownloadEntity
import com.rshop.data.database.entity.FavoriteEntity
import com.rshop.data.database.entity.GameEntity
import com.rshop.data.database.entity.HistoryEntity
import com.rshop.data.database.entity.InstalledGameEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE game_id = :gameId)")
    fun observeIsFavorite(gameId: String): Flow<Boolean>

    @Query("SELECT games.* FROM games JOIN favorites ON favorites.game_id = games.id ORDER BY favorites.added_at DESC")
    fun observeFavoriteGames(): Flow<List<GameEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE game_id = :gameId")
    suspend fun delete(gameId: String)
}

@Dao
interface HistoryDao {

    @Insert
    suspend fun insert(entry: HistoryEntity)

    /** Distinct games with at least one event of [type], most recent first. */
    @Query(
        """
        SELECT games.* FROM games
        JOIN (SELECT game_id, MAX(timestamp) AS last_seen FROM history WHERE type = :type GROUP BY game_id) recent
          ON recent.game_id = games.id
        ORDER BY recent.last_seen DESC
        LIMIT :limit
        """,
    )
    fun observeRecentGames(type: String, limit: Int): Flow<List<GameEntity>>

    /** Keeps the log bounded; called opportunistically when writing. */
    @Query("DELETE FROM history WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

/** An installed game plus what the catalogue currently says about it. */
data class InstalledWithCatalog(
    @Embedded val installed: InstalledGameEntity,
    @ColumnInfo(name = "catalog_version") val catalogVersion: String?,
    @ColumnInfo(name = "in_catalog") val inCatalog: Boolean,
)

@Dao
interface InstalledGameDao {

    @Query(
        """
        SELECT installed_games.*, games.version AS catalog_version, (games.id IS NOT NULL) AS in_catalog
        FROM installed_games LEFT JOIN games ON games.id = installed_games.game_id
        ORDER BY installed_games.title COLLATE NOCASE
        """,
    )
    fun observeWithCatalog(): Flow<List<InstalledWithCatalog>>

    @Query(
        """
        SELECT installed_games.*, games.version AS catalog_version, (games.id IS NOT NULL) AS in_catalog
        FROM installed_games LEFT JOIN games ON games.id = installed_games.game_id
        WHERE installed_games.game_id = :gameId
        """,
    )
    fun observeWithCatalog(gameId: String): Flow<InstalledWithCatalog?>

    @Query("SELECT * FROM installed_games ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<InstalledGameEntity>>

    @Query("SELECT * FROM installed_games WHERE game_id = :gameId")
    fun observe(gameId: String): Flow<InstalledGameEntity?>

    @Query("SELECT * FROM installed_games WHERE game_id = :gameId")
    suspend fun get(gameId: String): InstalledGameEntity?

    @Upsert
    suspend fun upsert(game: InstalledGameEntity)

    @Query("DELETE FROM installed_games WHERE game_id = :gameId")
    suspend fun delete(gameId: String)
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE game_id = :gameId")
    fun observe(gameId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE game_id = :gameId")
    suspend fun get(gameId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN (:states)")
    suspend fun getByStates(states: List<String>): List<DownloadEntity>

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE downloads SET downloaded_bytes = :downloaded, total_bytes = :total, updated_at = :now WHERE game_id = :gameId")
    suspend fun updateProgress(gameId: String, downloaded: Long, total: Long?, now: Long)

    @Query("UPDATE downloads SET state = :state, error = :error, updated_at = :now WHERE game_id = :gameId")
    suspend fun updateState(gameId: String, state: String, error: String?, now: Long)

    @Query("DELETE FROM downloads WHERE game_id = :gameId")
    suspend fun delete(gameId: String)
}
