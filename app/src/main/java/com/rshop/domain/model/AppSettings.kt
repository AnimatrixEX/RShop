package com.rshop.domain.model


data class AppSettings(
    /** Persisted SAF tree URI of the folder games are installed into when none is chosen, as a string. */
    val gamesDirectoryUri: String? = null,
    /** Every games folder the user added (the default one included), in the order they were added. */
    val gamesDirectoryUris: List<String> = emptyList(),
    val wifiOnly: Boolean = true,
    val deleteArchivesAfterInstall: Boolean = true,
    /** Downloads wait while the battery is low (Android's own "battery low" state). */
    val pauseOnLowBattery: Boolean = true,
    /** Space that must stay free on the device after a download, in megabytes. */
    val minFreeSpaceMb: Int = DEFAULT_FREE_SPACE_MB,
    /** Looks for a new RShop version about once a day, when the app opens. */
    val autoCheckUpdates: Boolean = true,
    /**
     * Game pages are read before the player opens them: in the background for favorites and the most
     * popular games, and for the game in focus. Off by default: it costs network and battery.
     */
    val readPagesAhead: Boolean = false,
    /** Epoch milliseconds of the last automatic look for a new version; 0 when never. */
    val lastUpdateCheckAt: Long = 0,
    /** Catalogue syncs (automatic ones, download counters) are off until the user turns them back on. */
    val syncPaused: Boolean = false,
    val theme: ThemeSettings = ThemeSettings(),
    val catalog: CatalogPrefs = CatalogPrefs(),
)

const val DEFAULT_FREE_SPACE_MB = 1024

/** The free-space margins the settings offer, in megabytes. */
val FREE_SPACE_CHOICES_MB = listOf(256, 1024, 2048, 5120)

/** How the Store narrows the catalogue; kept between launches. */
data class CatalogPrefs(
    val hideInstalled: Boolean = false,
    val hideExtras: Boolean = false,
    /** The filter rows of the Store are shown; hidden by default, they take a lot of the screen. */
    val filtersExpanded: Boolean = false,
)
