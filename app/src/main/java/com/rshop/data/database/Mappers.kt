package com.rshop.data.database

import com.rshop.data.database.entity.GameEntity
import com.rshop.data.database.entity.GameWithScreenshots
import com.rshop.data.database.entity.ScreenshotEntity
import com.rshop.domain.catalog.TitleTags
import com.rshop.domain.genre.TagCodec
import com.rshop.domain.model.Game
import java.time.Instant

fun GameEntity.toDomain(screenshots: List<String> = emptyList()): Game = Game(
    id = id,
    title = title,
    description = description,
    coverUrl = coverUrl,
    screenshots = screenshots,
    downloadUrl = downloadUrl,
    downloadViaPage = downloadViaPage,
    version = version,
    sizeBytes = sizeBytes,
    platform = platform,
    genre = genre,
    tags = TagCodec.decode(tags),
    sourceUrl = sourceUrl,
    sha256 = sha256,
    addedAt = addedAt?.let(Instant::ofEpochMilli),
    updatedAt = updatedAt?.let(Instant::ofEpochMilli),
    popularity = popularity,
    downloadCount = downloadCount,
    detailsSyncedAt = detailsSyncedAt?.let(Instant::ofEpochMilli),
    downloadOptions = DownloadOptionsJson.decode(downloadOptions),
    descriptionSource = descriptionSource,
)

fun GameWithScreenshots.toDomain(): Game =
    game.toDomain(screenshots.sortedBy { it.position }.map { it.url })

/** [sourceId] is the part of the id before the first ':' (ids are namespaced per source). */
fun Game.toEntity(syncedAt: Long): GameWithScreenshots = GameWithScreenshots(
    game = GameEntity(
        id = id,
        sourceId = id.substringBefore(':', missingDelimiterValue = ""),
        title = title,
        description = description,
        coverUrl = coverUrl,
        downloadUrl = downloadUrl,
        downloadViaPage = downloadViaPage,
        version = version,
        sizeBytes = sizeBytes,
        platform = platform,
        genre = genre,
        tags = TagCodec.encode(tags),
        sourceUrl = sourceUrl,
        sha256 = sha256,
        addedAt = addedAt?.toEpochMilli(),
        updatedAt = updatedAt?.toEpochMilli(),
        popularity = popularity,
        downloadCount = downloadCount,
        lastSyncedAt = syncedAt,
        detailsSyncedAt = detailsSyncedAt?.toEpochMilli(),
        downloadOptions = DownloadOptionsJson.encode(downloadOptions),
        isExtra = TitleTags.isExtra(title),
    ),
    screenshots = screenshots.mapIndexed { index, url -> ScreenshotEntity(gameId = id, position = index, url = url) },
)
