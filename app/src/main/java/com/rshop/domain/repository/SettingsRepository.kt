package com.rshop.domain.repository

import com.rshop.domain.model.AppSettings
import com.rshop.domain.model.CatalogPrefs
import com.rshop.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setGamesDirectoryUri(uri: String?)
    suspend fun setWifiOnly(enabled: Boolean)
    suspend fun setDeleteArchivesAfterInstall(enabled: Boolean)
    suspend fun setPauseOnLowBattery(enabled: Boolean)
    suspend fun setMinFreeSpaceMb(megabytes: Int)
    suspend fun setAutoCheckUpdates(enabled: Boolean)
    suspend fun setReadPagesAhead(enabled: Boolean)
    suspend fun setLastUpdateCheckAt(epochMillis: Long)
    suspend fun setSyncPaused(paused: Boolean)
    suspend fun setTheme(theme: ThemeSettings)
    suspend fun setCatalogPrefs(prefs: CatalogPrefs)
}
