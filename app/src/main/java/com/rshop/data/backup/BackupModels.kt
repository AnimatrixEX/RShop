package com.rshop.data.backup

import com.rshop.scraper.config.DriveConfig
import com.rshop.scraper.config.FtpConfig
import com.rshop.scraper.config.JsonApiConfig
import com.rshop.scraper.config.ScraperConfig
import com.rshop.scraper.config.SourceConfig
import com.rshop.scraper.remote.RemoteUrl
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Everything worth keeping when the app is reinstalled or moved to another device: the sources,
 * the favorites and lists, and the settings. Not in it: downloaded files (they live in the games
 * folder), the games folder permission (Android grants it per install) and the SteamGridDB key
 * (a secret, entered again).
 */
@Serializable
data class Backup(
    val format: Int = FORMAT,
    val appVersion: String = "",
    /** Epoch milliseconds. */
    val createdAt: Long = 0,
    /** Website sources (the only kind of older versions). */
    val sources: List<ScraperConfig> = emptyList(),
    /** Google Drive sources, apart so that older versions still read the website ones. */
    val driveSources: List<DriveConfig> = emptyList(),
    /** JSON API sources, apart for the same reason. */
    val jsonSources: List<JsonApiConfig> = emptyList(),
    /** FTP, FTPS and SFTP servers. Their passwords are not in the backup: they are entered again. */
    val ftpSources: List<FtpConfig> = emptyList(),
    val favorites: List<BackupGame> = emptyList(),
    val lists: List<BackupList> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
) {
    val allSources: List<SourceConfig> get() = sources + driveSources + jsonSources + ftpSources

    /** Every server the sources would contact (catalogue and permitted download hosts), to show before a restore. */
    val hosts: List<String>
        get() = allSources.flatMap { source ->
            val own = source.location.toHttpUrlOrNull()?.host ?: RemoteUrl.parse(source.location)?.host ?: source.location
            val downloads = when (source) {
                is ScraperConfig -> source.allowedDownloadHosts
                is JsonApiConfig -> source.allowedDownloadHosts
                else -> emptyList()
            }
            listOf(own) + downloads
        }.distinct()

    companion object {
        const val FORMAT = 1

        fun sourcesOf(all: List<SourceConfig>): Backup = Backup(
            sources = all.filterIsInstance<ScraperConfig>(),
            driveSources = all.filterIsInstance<DriveConfig>(),
            jsonSources = all.filterIsInstance<JsonApiConfig>(),
            ftpSources = all.filterIsInstance<FtpConfig>(),
        )
    }
}

/** A game by id; the title only makes the file readable. */
@Serializable
data class BackupGame(val id: String, val title: String = "", val platform: String? = null)

@Serializable
data class BackupList(val name: String, val games: List<BackupGame> = emptyList())

@Serializable
data class BackupSettings(
    val wifiOnly: Boolean = true,
    val deleteArchivesAfterInstall: Boolean = true,
    val themeBase: String? = null,
    val themeAccent: String? = null,
    val themeFocus: String? = null,
    val themeTextSize: String? = null,
    val themeCoverStyle: String? = null,
    val animatedBackground: Boolean = false,
    val dynamicBackdrop: Boolean = true,
    val pauseOnLowBattery: Boolean = true,
    val minFreeSpaceMb: Int = 1024,
    val autoCheckUpdates: Boolean = true,
    val readPagesAhead: Boolean = false,
    val hideInstalled: Boolean = false,
    val hideExtras: Boolean = false,
    /** Language tag ("fr", "en"); null follows the system. */
    val language: String? = null,
)

/** What a backup holds, shown before it is restored. */
data class BackupSummary(
    val createdAt: Long,
    val appVersion: String,
    val sources: List<String>,
    /** Servers the sources will contact (catalogue and download hosts), so a restored file cannot point anywhere unseen. */
    val hosts: List<String>,
    val favorites: Int,
    val lists: Int,
)

/** What a restore did. Games not in the catalogue yet are applied once a sync has brought them. */
data class RestoreReport(
    val sources: Int,
    val favorites: Int,
    val lists: Int,
    /** Favorites and list entries waiting for their game to be synced. */
    val waiting: Int,
)

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
