package com.rshop.domain.model

import java.time.Instant

data class InstalledGame(
    val gameId: String,
    val title: String,
    val platform: String?,
    val coverUrl: String?,
    val installedVersion: String?,
    /** Version currently published by the source; null when unknown or no longer listed. */
    val catalogVersion: String?,
    val inCatalog: Boolean,
    val sizeOnDisk: Long?,
    val installedAt: Instant,
) {
    /** Phase 9 replaces this plain comparison with real version ordering. */
    val updateAvailable: Boolean
        get() = inCatalog && catalogVersion != null && installedVersion != null && catalogVersion != installedVersion
}
