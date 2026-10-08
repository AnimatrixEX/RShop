package com.rshop.scraper.model

/**
 * A catalogue entry as extracted from a source. Every field except [id] and [title] is optional.
 * [id] is source-relative (for websites: the path of the game page) and stable across syncs.
 */
data class ScrapedGame(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    val platform: String? = null,
    val genre: String? = null,
    val version: String? = null,
    val sizeBytes: Long? = null,
    val detailsUrl: String? = null,
    /** How many times the source says the game was downloaded, when it shows it. */
    val downloadCount: Long? = null,
)

data class ScrapedGameDetails(
    val game: ScrapedGame,
    val description: String? = null,
    val screenshots: List<String> = emptyList(),
    /** Only links that passed URL validation (scheme and allowed hosts). */
    val downloads: List<ScrapedDownload> = emptyList(),
    /** Last update date as an ISO-8601 instant when the source provides one. */
    val updatedAt: String? = null,
)

data class ScrapedDownload(
    val url: String,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    /** Lower-case hex SHA-256, only when the page publishes a well-formed one. */
    val sha256: String? = null,
    /** [url] is the site's download page; resolve it with [com.rshop.scraper.GameSource.resolveDownload]. */
    val viaPage: Boolean = false,
    /** What tells this file apart from the game's other files: format, disc, region ("ZIP", "Disc 2 · CHD"). */
    val label: String? = null,
)

/**
 * What the scraper hands to the downloader: a URL that serves the file itself (checked with one
 * request whose body is not read), plus what its headers said.
 */
data class DownloadInfo(
    val url: String,
    /** From Content-Disposition, else the last segment of the final URL. Not sanitised. */
    val fileName: String?,
    val sizeBytes: Long?,
    val contentType: String?,
    /** Page the link was found on (the game page or the last download page). */
    val sourcePage: String,
)

data class CatalogPage(
    val games: List<ScrapedGame>,
    val hasNext: Boolean,
    /** Console section the page belongs to, for sites organised by console. */
    val section: String? = null,
)

data class CatalogSection(val name: String, val url: String)
