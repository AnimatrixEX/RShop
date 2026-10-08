package com.rshop.data.repository

import com.rshop.data.database.entity.GameEntity
import com.rshop.domain.genre.TagCodec

/**
 * How fresh data from the source is combined with what the database already knows.
 * Pure functions, so the rules are unit-tested on their own.
 */
internal object CatalogMerge {

    /** A listing page only knows a few fields: everything else is kept from earlier syncs. */
    fun listing(old: GameEntity?, new: GameEntity, now: Long): GameEntity {
        if (old == null) {
            return new.copy(addedAt = new.addedAt ?: now, updatedAt = new.updatedAt ?: now, lastSyncedAt = now)
        }
        val versionChanged = versionChanged(old.version, new.version)
        return new.copy(
            description = new.description ?: old.description,
            // A game page usually has a larger cover than the listing thumbnail.
            coverUrl = if (old.detailsSyncedAt != null) old.coverUrl ?: new.coverUrl else new.coverUrl ?: old.coverUrl,
            downloadUrl = new.downloadUrl ?: old.downloadUrl,
            downloadViaPage = if (new.downloadUrl != null) new.downloadViaPage else old.downloadViaPage,
            downloadOptions = new.downloadOptions ?: old.downloadOptions,
            sha256 = new.sha256 ?: old.sha256,
            version = new.version ?: old.version,
            sizeBytes = new.sizeBytes ?: old.sizeBytes,
            platform = new.platform ?: old.platform,
            genre = new.genre ?: old.genre,
            tags = mergeTags(old.tags, new.tags),
            sourceUrl = new.sourceUrl ?: old.sourceUrl,
            addedAt = old.addedAt ?: new.addedAt ?: now,
            updatedAt = if (versionChanged) now else new.updatedAt ?: old.updatedAt,
            popularity = new.popularity.takeIf { it > 0 } ?: old.popularity,
            downloadCount = new.downloadCount ?: old.downloadCount,
            lastSyncedAt = now,
            // A new version means the files and hash on the game page changed too.
            detailsSyncedAt = if (versionChanged) null else old.detailsSyncedAt,
            artworkCheckedAt = old.artworkCheckedAt,
        )
    }

    /** A game page is authoritative for what it provides; missing fields keep previous values. */
    fun details(old: GameEntity?, new: GameEntity, now: Long): GameEntity {
        val versionChanged = old != null && versionChanged(old.version, new.version)
        return new.copy(
            description = new.description ?: old?.description,
            coverUrl = new.coverUrl ?: old?.coverUrl,
            downloadUrl = new.downloadUrl ?: old?.downloadUrl,
            downloadViaPage = if (new.downloadUrl != null) new.downloadViaPage else old?.downloadViaPage ?: false,
            downloadOptions = new.downloadOptions ?: old?.downloadOptions,
            sha256 = new.sha256 ?: old?.sha256,
            version = new.version ?: old?.version,
            sizeBytes = new.sizeBytes ?: old?.sizeBytes,
            platform = new.platform ?: old?.platform,
            genre = new.genre ?: old?.genre,
            tags = mergeTags(old?.tags, new.tags),
            sourceUrl = new.sourceUrl ?: old?.sourceUrl,
            addedAt = old?.addedAt ?: new.addedAt ?: now,
            updatedAt = new.updatedAt ?: (if (versionChanged) now else old?.updatedAt) ?: now,
            popularity = new.popularity.takeIf { it > 0 } ?: old?.popularity ?: 0,
            downloadCount = new.downloadCount ?: old?.downloadCount,
            lastSyncedAt = old?.lastSyncedAt ?: now,
            detailsSyncedAt = now,
            artworkCheckedAt = old?.artworkCheckedAt,
        )
    }

    /** Tags found by the listing and by the game page add up (the page also has the description). */
    private fun mergeTags(old: String?, new: String?): String? = TagCodec.encode(TagCodec.decode(new) + TagCodec.decode(old))

    private fun versionChanged(old: String?, new: String?) = old != null && new != null && old != new
}
