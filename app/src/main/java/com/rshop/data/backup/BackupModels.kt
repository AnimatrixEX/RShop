package com.rshop.data.backup

import com.rshop.scraper.config.ScraperConfig
import kotlinx.serialization.Serializable

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
    val sources: List<ScraperConfig> = emptyList(),
    val favorites: List<BackupGame> = emptyList(),
    val lists: List<BackupList> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
) {
    companion object {
        const val FORMAT = 1
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
    val dynamicBackdrop: Boolean = true,
    val hideInstalled: Boolean = false,
    val hideExtras: Boolean = false,
    val region: String? = null,
    /** Language tag ("fr", "en"); null follows the system. */
    val language: String? = null,
)

/** What a backup holds, shown before it is restored. */
data class BackupSummary(
    val createdAt: Long,
    val appVersion: String,
    val sources: List<String>,
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
