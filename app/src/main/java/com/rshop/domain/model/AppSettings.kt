package com.rshop.domain.model

import com.rshop.domain.catalog.CatalogRegion

data class AppSettings(
    /** Persisted SAF tree URI of the games folder, as a string. */
    val gamesDirectoryUri: String? = null,
    val wifiOnly: Boolean = true,
    val deleteArchivesAfterInstall: Boolean = true,
    /** Catalogue syncs (automatic ones, download counters) are off until the user turns them back on. */
    val syncPaused: Boolean = false,
    val theme: ThemeSettings = ThemeSettings(),
    val catalog: CatalogPrefs = CatalogPrefs(),
)

/** How the Store narrows the catalogue; kept between launches. */
data class CatalogPrefs(
    val hideInstalled: Boolean = false,
    val hideExtras: Boolean = false,
    val region: CatalogRegion = CatalogRegion.All,
)
