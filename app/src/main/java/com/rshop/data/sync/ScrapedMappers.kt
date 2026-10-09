package com.rshop.data.sync

import com.rshop.domain.genre.GenreClassifier
import com.rshop.domain.model.DownloadOption
import com.rshop.domain.model.Game
import com.rshop.scraper.model.ScrapedGame
import com.rshop.scraper.model.ScrapedGameDetails
import java.time.Instant
import java.time.format.DateTimeParseException

/** Catalogue ids are namespaced by source so several sources can never collide. */
fun gameId(sourceId: String, scrapedId: String) = "$sourceId:$scrapedId"

fun ScrapedGame.toDomain(sourceId: String) = Game(
    id = gameId(sourceId, id),
    title = title,
    description = null,
    // Covers come from SteamGridDB (see ArtworkResolver), never from the source site.
    coverUrl = null,
    screenshots = emptyList(),
    downloadUrl = null,
    version = version,
    sizeBytes = sizeBytes,
    platform = platform,
    genre = genre,
    tags = GenreClassifier.classify(genre, title, description = null, platform = platform),
    sourceUrl = detailsUrl,
    downloadCount = downloadCount,
    // The site's own download counter is the best popularity signal there is.
    popularity = downloadCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
)

fun ScrapedGameDetails.toDomain(sourceId: String): Game {
    // Several files (formats, discs…): all are offered, the first one is the default.
    val download = downloads.firstOrNull()
    return game.toDomain(sourceId).copy(
        downloadOptions = downloads.map {
            DownloadOption(url = it.url, label = it.label, fileName = it.fileName, sizeBytes = it.sizeBytes, sha256 = it.sha256, viaPage = it.viaPage, isUpdate = it.isUpdate, isExtra = it.isExtra)
        },
        description = description,
        tags = GenreClassifier.classify(game.genre, game.title, description, game.platform),
        screenshots = screenshots,
        downloadUrl = download?.url,
        downloadViaPage = download?.viaPage ?: false,
        sha256 = download?.sha256,
        sizeBytes = download?.sizeBytes ?: game.sizeBytes,
        updatedAt = updatedAt?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        },
    )
}
