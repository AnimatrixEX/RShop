package com.rshop.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** A catalogue entry. Timestamps are epoch milliseconds. */
@Entity(
    tableName = "games",
    indices = [
        Index("platform"),
        Index("genre"),
        Index("added_at"),
        Index("updated_at"),
        Index("popularity"),
    ],
)
data class GameEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    val description: String?,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "download_url") val downloadUrl: String?,
    /** [downloadUrl] is the site's download page (countdown…), resolved when downloading. */
    @ColumnInfo(name = "download_via_page", defaultValue = "0") val downloadViaPage: Boolean = false,
    val version: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    val platform: String?,
    val genre: String?,
    /** Genres and categories as "|rpg|Strategy|" (see TagCodec); derived from [genre], title and description. */
    val tags: String? = null,
    @ColumnInfo(name = "source_url") val sourceUrl: String?,
    val sha256: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long?,
    val popularity: Int,
    @ColumnInfo(name = "download_count") val downloadCount: Long? = null,
    /** When the last sync saw this entry; lets a sync detect games removed from the source. */
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long,
    /** When the game page itself (description, files, hash) was last read; null if never. */
    @ColumnInfo(name = "details_synced_at") val detailsSyncedAt: Long? = null,
    /** Every file of the game page as JSON (see DownloadOptionsJson); null until the page is read. */
    @ColumnInfo(name = "download_options") val downloadOptions: String? = null,
    /**
     * When SteamGridDB was last asked for this game's cover ([coverUrl] then holds its answer,
     * possibly none); null while never asked. Site covers are not used.
     */
    @ColumnInfo(name = "artwork_checked_at") val artworkCheckedAt: Long? = null,
    /**
     * When the game page was last read for its download counter, which many listings do not
     * show; null while never looked up (or while the listing gives the counter itself).
     */
    @ColumnInfo(name = "stats_checked_at") val statsCheckedAt: Long? = null,
)

/** Full-text index kept in sync with [GameEntity] by triggers Room generates. */
@Fts4(contentEntity = GameEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "games_fts")
data class GameFtsEntity(
    val title: String,
    val platform: String?,
    val genre: String?,
)

@Entity(
    tableName = "screenshots",
    primaryKeys = ["game_id", "position"],
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["game_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ScreenshotEntity(
    @ColumnInfo(name = "game_id") val gameId: String,
    val position: Int,
    val url: String,
)

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["game_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "game_id") val gameId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** A list the user made ("À finir", "Co-op"…). Favorites stay their own built-in list. */
@Entity(tableName = "game_lists")
data class GameListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "game_list_entries",
    primaryKeys = ["list_id", "game_id"],
    indices = [Index("game_id")],
    foreignKeys = [
        ForeignKey(entity = GameListEntity::class, parentColumns = ["id"], childColumns = ["list_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["game_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class GameListEntryEntity(
    @ColumnInfo(name = "list_id") val listId: Long,
    @ColumnInfo(name = "game_id") val gameId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Append-only user activity log (game pages opened, later downloads and installs). */
@Entity(
    tableName = "history",
    indices = [Index("game_id"), Index("timestamp")],
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["game_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "game_id") val gameId: String,
    val type: String,
    val timestamp: Long,
)

/**
 * A game present in the user's games folder. Deliberately no foreign key to [GameEntity]:
 * an installed game must stay in the library even if it disappears from the source catalogue.
 */
@Entity(tableName = "installed_games")
data class InstalledGameEntity(
    @PrimaryKey @ColumnInfo(name = "game_id") val gameId: String,
    val title: String,
    val platform: String?,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "installed_version") val installedVersion: String?,
    /** SAF document URI of the game's folder or file inside the games directory. */
    /** The game file or folder in the games folder; several files (bin + cue...) are listed one per line. */
    @ColumnInfo(name = "document_uri") val documentUri: String,
    @ColumnInfo(name = "size_on_disk") val sizeOnDisk: Long?,
    @ColumnInfo(name = "installed_at") val installedAt: Long,
    /** Format of the installed files ("GBA", "BIN + CUE"); null until known (older installs are read lazily). */
    @ColumnInfo(name = "file_format") val fileFormat: String? = null,
)

/** One download per game: the file being fetched, verified and installed. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey @ColumnInfo(name = "game_id") val gameId: String,
    /** Copied from the catalogue so the queue still displays if the game leaves the catalogue. */
    val title: String,
    val platform: String?,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    val url: String,
    /** [url] is a download page to resolve (and wait on) before fetching the file. */
    @ColumnInfo(name = "via_page", defaultValue = "0") val viaPage: Boolean = false,
    @ColumnInfo(name = "file_name") val fileName: String,
    /** Absolute path of the partial/complete file in app-specific storage. */
    @ColumnInfo(name = "temp_path") val tempPath: String,
    @ColumnInfo(name = "expected_sha256") val expectedSha256: String?,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long?,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long,
    /** Validators sent back with Range requests so a changed file is never resumed. */
    val etag: String?,
    @ColumnInfo(name = "last_modified") val lastModified: String?,
    val state: String,
    val error: String?,
    /** True once the SHA-256 published by the source matched. */
    val verified: Boolean,
    /** Catalogue version this download installs. */
    val version: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Page the file link was found on, sent as Referer like a browser click would. */
    @ColumnInfo(name = "referer") val referer: String? = null,
    /**
     * Set for a file the user clicked in the in-app browser: that browser session's cookies and
     * User-Agent, sent with this file's requests only. The cookies are dropped once it is downloaded.
     */
    @ColumnInfo(name = "request_cookie") val requestCookie: String? = null,
    @ColumnInfo(name = "request_user_agent") val requestUserAgent: String? = null,
)

data class GameWithScreenshots(
    @Embedded val game: GameEntity,
    @Relation(parentColumn = "id", entityColumn = "game_id")
    val screenshots: List<ScreenshotEntity>,
)
