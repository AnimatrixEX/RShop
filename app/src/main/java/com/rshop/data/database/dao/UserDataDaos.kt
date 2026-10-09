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
import com.rshop.data.database.entity.GameListEntity
import com.rshop.data.database.entity.GameListEntryEntity
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

    @Query("SELECT game_id FROM installed_games")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT * FROM installed_games ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<InstalledGameEntity>>

    @Query("SELECT * FROM installed_games WHERE game_id = :gameId")
    fun observe(gameId: String): Flow<InstalledGameEntity?>

    @Query("SELECT * FROM installed_games WHERE game_id = :gameId")
    suspend fun get(gameId: String): InstalledGameEntity?

    @Query("SELECT * FROM installed_games")
    suspend fun getAll(): List<InstalledGameEntity>

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

data class GameListWithCount(
    val id: Long,
    val name: String,
    val games: Int,
)

data class ListName(val id: Long, val name: String)

data class ListEntryRow(val listId: Long, val gameId: String, val title: String, val platform: String?)

@Dao
interface GameListDao {

    @Query(
        """
        SELECT game_lists.id AS id, game_lists.name AS name, COUNT(game_list_entries.game_id) AS games
        FROM game_lists LEFT JOIN game_list_entries ON game_list_entries.list_id = game_lists.id
        GROUP BY game_lists.id
        ORDER BY game_lists.created_at
        """,
    )
    fun observeLists(): Flow<List<GameListWithCount>>

    @Query(
        """
        SELECT games.* FROM games JOIN game_list_entries ON game_list_entries.game_id = games.id
        WHERE game_list_entries.list_id = :listId ORDER BY game_list_entries.added_at DESC
        """,
    )
    fun observeGames(listId: Long): Flow<List<GameEntity>>

    @Query("SELECT list_id FROM game_list_entries WHERE game_id = :gameId")
    fun observeListIdsOf(gameId: String): Flow<List<Long>>

    @Query("SELECT id, name FROM game_lists ORDER BY created_at")
    suspend fun listNames(): List<ListName>

    @Query(
        """
        SELECT game_list_entries.list_id AS listId, games.id AS gameId, games.title AS title, games.platform AS platform
        FROM game_list_entries JOIN games ON games.id = game_list_entries.game_id
        ORDER BY game_list_entries.added_at
        """,
    )
    suspend fun entriesWithGames(): List<ListEntryRow>

    @Insert
    suspend fun insertList(list: GameListEntity): Long

    @Query("UPDATE game_lists SET name = :name WHERE id = :listId")
    suspend fun rename(listId: Long, name: String)

    @Query("DELETE FROM game_lists WHERE id = :listId")
    suspend fun delete(listId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addEntry(entry: GameListEntryEntity)

    @Query("DELETE FROM game_list_entries WHERE list_id = :listId AND game_id = :gameId")
    suspend fun removeEntry(listId: Long, gameId: String)
}
