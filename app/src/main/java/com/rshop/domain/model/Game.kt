package com.rshop.domain.model

import java.time.Instant

data class Game(
    val id: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val screenshots: List<String>,
    val downloadUrl: String?,
    /** [downloadUrl] points to the site's download page rather than to the file itself. */
    val downloadViaPage: Boolean = false,
    val version: String?,
    val sizeBytes: Long?,
    val platform: String?,
    val genre: String?,
    val sourceUrl: String?,
    val sha256: String? = null,
    val addedAt: Instant? = null,
    val updatedAt: Instant? = null,
    /** Popularity score, higher is more popular: the download count when the source shows one. */
    val popularity: Int = 0,
    /** How many times the source says the game was downloaded; null when it does not say. */
    val downloadCount: Long? = null,
    /** When the game page (description, files, hash) was last read from the source; null if never. */
    val detailsSyncedAt: Instant? = null,
    /** Genre tags (see GenreClassifier): known genre keys first, then the site's own categories. */
    val tags: List<String> = emptyList(),
    /** Every file the game page offers (formats, discs…); [downloadUrl] is the first one. */
    val downloadOptions: List<DownloadOption> = emptyList(),
)

/** One file a game page offers. [label] tells it apart from the others ("ZIP", "Disc 2 · CHD"). */
data class DownloadOption(
    val url: String,
    val label: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    /** [url] must be resolved by the source (download page, redirect) before downloading. */
    val viaPage: Boolean = false,
)

/** Source id is the namespace before the first ':' of a game id (e.g. "homebrew-hub:/game/x"). */
val Game.sourceId: String get() = id.substringBefore(':', missingDelimiterValue = "")
