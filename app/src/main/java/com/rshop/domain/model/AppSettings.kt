package com.rshop.domain.model

data class AppSettings(
    /** Persisted SAF tree URI of the games folder, as a string. */
    val gamesDirectoryUri: String? = null,
    val wifiOnly: Boolean = true,
    val deleteArchivesAfterInstall: Boolean = true,
    val theme: ThemeSettings = ThemeSettings(),
)
