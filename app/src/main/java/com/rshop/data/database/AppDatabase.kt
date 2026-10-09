package com.rshop.data.database

import androidx.room.AutoMigration
import androidx.room.DeleteColumn
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rshop.data.database.dao.DownloadDao
import com.rshop.data.database.dao.FavoriteDao
import com.rshop.data.database.dao.GameDao
import com.rshop.data.database.dao.GameListDao
import com.rshop.domain.catalog.TitleTags
import com.rshop.domain.genre.GenreClassifier
import com.rshop.domain.genre.TagCodec
import com.rshop.data.database.dao.HistoryDao
import com.rshop.data.database.dao.InstalledGameDao
import com.rshop.data.database.entity.DownloadEntity
import com.rshop.data.database.entity.FavoriteEntity
import com.rshop.data.database.entity.GameEntity
import com.rshop.data.database.entity.GameFtsEntity
import com.rshop.data.database.entity.GameListEntity
import com.rshop.data.database.entity.GameListEntryEntity
import com.rshop.data.database.entity.HistoryEntity
import com.rshop.data.database.entity.InstalledGameEntity
import com.rshop.data.database.entity.ScreenshotEntity

@Database(
    entities = [
        GameEntity::class,
        GameFtsEntity::class,
        ScreenshotEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        InstalledGameEntity::class,
        DownloadEntity::class,
        GameListEntity::class,
        GameListEntryEntity::class,
    ],
    version = 13,
    exportSchema = true,
    autoMigrations = [
        // v2: games.details_synced_at + downloads table.
        AutoMigration(from = 1, to = 2),
        // v3: games.download_options (one entry per format/file offered by the game page).
        AutoMigration(from = 2, to = 3),
        // v4: downloads.referer.
        AutoMigration(from = 3, to = 4),
        // v5: games.artwork_checked_at; covers now come from SteamGridDB, not from the sites.
        AutoMigration(from = 4, to = 5, spec = AppDatabase.DropSiteCovers::class),
        // v6: downloads.request_cookie + request_user_agent (files picked in the in-app browser).
        AutoMigration(from = 5, to = 6),
        // Download counters read from the source.
        AutoMigration(from = 6, to = 7),
        // v8: games.tags (genre detection) + custom game lists.
        AutoMigration(from = 7, to = 8, spec = AppDatabase.AddGenreTags::class),
        // v9: installed_games.file_format.
        AutoMigration(from = 8, to = 9),
        // v10: games.stats_checked_at (download counters read from game pages in the background).
        AutoMigration(from = 9, to = 10),
        // v11: games.region_flags + is_extra (catalogue filters) and the several-files columns of downloads.
        AutoMigration(from = 10, to = 11, spec = AppDatabase.AddTitleTags::class),
        // v12: games.region_flags removed again (a title does not tell which region the user wants).
        AutoMigration(from = 11, to = 12, spec = AppDatabase.DropRegionFlags::class),
        // v13: games.description_source / description_checked_at / screenshots_checked_at (infos from outside the catalogue source).
        AutoMigration(from = 12, to = 13),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun installedGameDao(): InstalledGameDao
    abstract fun downloadDao(): DownloadDao
    abstract fun gameListDao(): GameListDao

    /** Covers taken from the sites before v5 are forgotten; SteamGridDB fills them again. */
    class DropSiteCovers : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE games SET cover_url = NULL")
            db.execSQL("UPDATE installed_games SET cover_url = NULL")
            db.execSQL("UPDATE downloads SET cover_url = NULL")
        }
    }

    /** Derives the tags of every game already in the catalogue, so filters work without a new sync. */
    class AddGenreTags : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            val updates = ArrayList<Pair<String, String>>()
            db.query("SELECT id, title, genre, description, platform FROM games").use { c ->
                while (c.moveToNext()) {
                    val tags = TagCodec.encode(
                        GenreClassifier.classify(
                            genre = c.getString(2),
                            title = c.getString(1),
                            description = c.getString(3),
                            platform = c.getString(4),
                        ),
                    ) ?: continue
                    updates += c.getString(0) to tags
                }
            }
            updates.forEach { (id, tags) -> db.execSQL("UPDATE games SET tags = ? WHERE id = ?", arrayOf(tags, id)) }
        }
    }

    @DeleteColumn(tableName = "games", columnName = "region_flags")
    class DropRegionFlags : AutoMigrationSpec

    /** Reads the demo/beta mark of every game already in the catalogue. */
    class AddTitleTags : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            val extras = ArrayList<String>()
            db.query("SELECT id, title FROM games").use { c ->
                while (c.moveToNext()) {
                    if (TitleTags.isExtra(c.getString(1))) extras += c.getString(0)
                }
            }
            extras.forEach { id -> db.execSQL("UPDATE games SET is_extra = 1 WHERE id = ?", arrayOf<Any>(id)) }
        }
    }

    companion object {
        const val NAME = "rshop.db"
    }
}
