package com.rshop.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rshop.domain.model.AppSettings
import com.rshop.domain.model.DEFAULT_FREE_SPACE_MB
import com.rshop.domain.model.CatalogPrefs
import com.rshop.domain.model.FocusStyle
import com.rshop.domain.model.CoverStyle
import com.rshop.domain.model.TextSize
import com.rshop.domain.model.ThemeAccent
import com.rshop.domain.model.ThemeBase
import com.rshop.domain.model.ThemeSettings
import com.rshop.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {

    private val dataStore = context.settingsDataStore

    private companion object {
        const val MAX_FREE_SPACE_MB = 100 * 1024
    }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                Timber.e(error, "Unable to read settings, falling back to defaults")
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            AppSettings(
                gamesDirectoryUri = prefs[Keys.GamesDirectoryUri],
                wifiOnly = prefs[Keys.WifiOnly] ?: true,
                deleteArchivesAfterInstall = prefs[Keys.DeleteArchives] ?: true,
                pauseOnLowBattery = prefs[Keys.PauseOnLowBattery] ?: true,
                minFreeSpaceMb = prefs[Keys.MinFreeSpaceMb] ?: DEFAULT_FREE_SPACE_MB,
                autoCheckUpdates = prefs[Keys.AutoCheckUpdates] ?: true,
                lastUpdateCheckAt = prefs[Keys.LastUpdateCheckAt] ?: 0L,
                syncPaused = prefs[Keys.SyncPaused] ?: false,
                theme = ThemeSettings(
                    base = prefs[Keys.ThemeBase].toEnum(ThemeBase.Night),
                    accent = prefs[Keys.ThemeAccent].toEnum(ThemeAccent.Blue),
                    focus = prefs[Keys.FocusStyle].toEnum(FocusStyle.White),
                    textSize = prefs[Keys.TextSize].toEnum(TextSize.Normal),
                    coverStyle = prefs[Keys.CoverStyle].toEnum(CoverStyle.Flat),
                    dynamicBackdrop = prefs[Keys.DynamicBackdrop] ?: true,
                ),
                catalog = CatalogPrefs(
                    hideInstalled = prefs[Keys.HideInstalled] ?: false,
                    hideExtras = prefs[Keys.HideExtras] ?: false,
                    filtersExpanded = prefs[Keys.FiltersExpanded] ?: false,
                ),
            )
        }

    override suspend fun setGamesDirectoryUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.GamesDirectoryUri) else prefs[Keys.GamesDirectoryUri] = uri
        }
    }

    override suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.WifiOnly] = enabled }
    }

    override suspend fun setDeleteArchivesAfterInstall(enabled: Boolean) {
        dataStore.edit { it[Keys.DeleteArchives] = enabled }
    }

    override suspend fun setPauseOnLowBattery(enabled: Boolean) {
        dataStore.edit { it[Keys.PauseOnLowBattery] = enabled }
    }

    override suspend fun setMinFreeSpaceMb(megabytes: Int) {
        dataStore.edit { it[Keys.MinFreeSpaceMb] = megabytes.coerceIn(0, MAX_FREE_SPACE_MB) }
    }

    override suspend fun setLastUpdateCheckAt(epochMillis: Long) {
        dataStore.edit { it[Keys.LastUpdateCheckAt] = epochMillis }
    }

    override suspend fun setAutoCheckUpdates(enabled: Boolean) {
        dataStore.edit { it[Keys.AutoCheckUpdates] = enabled }
    }

    override suspend fun setSyncPaused(paused: Boolean) {
        dataStore.edit { it[Keys.SyncPaused] = paused }
    }

    override suspend fun setTheme(theme: ThemeSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.ThemeBase] = theme.base.name
            prefs[Keys.ThemeAccent] = theme.accent.name
            prefs[Keys.FocusStyle] = theme.focus.name
            prefs[Keys.TextSize] = theme.textSize.name
            prefs[Keys.CoverStyle] = theme.coverStyle.name
            prefs[Keys.DynamicBackdrop] = theme.dynamicBackdrop
        }
    }

    override suspend fun setCatalogPrefs(prefs: CatalogPrefs) {
        dataStore.edit {
            it[Keys.HideInstalled] = prefs.hideInstalled
            it[Keys.HideExtras] = prefs.hideExtras
            it[Keys.FiltersExpanded] = prefs.filtersExpanded
        }
    }

    /** Stored by name; an unknown value (renamed or removed option) falls back to the default. */
    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private object Keys {
        val GamesDirectoryUri = stringPreferencesKey("games_directory_uri")
        val WifiOnly = booleanPreferencesKey("wifi_only")
        val DeleteArchives = booleanPreferencesKey("delete_archives_after_install")
        val SyncPaused = booleanPreferencesKey("sync_paused")
        val PauseOnLowBattery = booleanPreferencesKey("pause_on_low_battery")
        val MinFreeSpaceMb = intPreferencesKey("min_free_space_mb")
        val AutoCheckUpdates = booleanPreferencesKey("auto_check_updates")
        val LastUpdateCheckAt = longPreferencesKey("last_update_check_at")
        val ThemeBase = stringPreferencesKey("theme_base")
        val ThemeAccent = stringPreferencesKey("theme_accent")
        val FocusStyle = stringPreferencesKey("theme_focus")
        val TextSize = stringPreferencesKey("theme_text_size")
        val CoverStyle = stringPreferencesKey("theme_cover_style")
        val DynamicBackdrop = booleanPreferencesKey("theme_dynamic_backdrop")
        val HideInstalled = booleanPreferencesKey("catalog_hide_installed")
        val HideExtras = booleanPreferencesKey("catalog_hide_extras")
        val FiltersExpanded = booleanPreferencesKey("catalog_filters_expanded")
    }
}
