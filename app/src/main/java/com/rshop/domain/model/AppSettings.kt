package com.rshop.domain.model

data class AppSettings(
    /** Persisted SAF tree URI of the games folder, as a string. */
    val gamesDirectoryUri: String? = null,
    val wifiOnly: Boolean = true,
    val deleteArchivesAfterInstall: Boolean = true,
    /** Catalogue syncs (automatic ones, download counters) are off until the user turns them back on. */
    val syncPaused: Boolean = false,
    val theme: ThemeSettings = ThemeSettings(),
)
