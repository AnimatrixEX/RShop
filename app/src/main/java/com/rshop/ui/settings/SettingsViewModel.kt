package com.rshop.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.data.artwork.ArtworkResolver
import com.rshop.data.artwork.ArtworkScheduler
import com.rshop.data.artwork.ArtworkSettings
import com.rshop.data.artwork.ArtworkStatus
import com.rshop.data.preferences.AppLanguage
import com.rshop.data.preferences.AppLanguageController
import com.rshop.data.source.SourceRepository
import com.rshop.data.storage.GamesDirectoryManager
import com.rshop.data.sync.SyncScheduler
import com.rshop.data.sync.SyncState
import com.rshop.scraper.config.ScraperConfig
import com.rshop.data.storage.GamesDirectoryState
import com.rshop.domain.model.AppSettings
import com.rshop.domain.model.ThemeSettings
import com.rshop.ui.theme.ActiveTheme
import com.rshop.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val directory: GamesDirectoryState = GamesDirectoryState.NotSelected,
    val language: AppLanguage = AppLanguage.System,
    val sources: List<ScraperConfig> = emptyList(),
    val sync: SyncState = SyncState(),
    val artwork: ArtworkStatus = ArtworkStatus(),
    /** Games SteamGridDB has not been asked about yet. */
    val pendingArtwork: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val directoryManager: GamesDirectoryManager,
    private val languageController: AppLanguageController,
    sourceRepository: SourceRepository,
    private val syncScheduler: SyncScheduler,
    private val artworkSettings: ArtworkSettings,
    private val artworkResolver: ArtworkResolver,
    private val artworkScheduler: ArtworkScheduler,
) : ViewModel() {

    private val language = MutableStateFlow(languageController.current())

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        directoryManager.state,
        language,
        sourceRepository.configs,
        syncScheduler.state,
        ::SettingsUiState,
    ).combine(combine(artworkSettings.status, artworkResolver.pendingCount, ::Pair)) { state, (artwork, pending) ->
        state.copy(artwork = artwork, pendingArtwork = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(language = language.value))

    fun onDirectorySelected(uri: Uri) {
        viewModelScope.launch { directoryManager.select(uri) }
    }

    fun onSyncNow() {
        viewModelScope.launch { syncScheduler.syncNow() }
    }

    /** Stops a running sync. Games already read stay; a later sync starts it again. */
    fun onPauseSync() {
        syncScheduler.cancel()
    }

    fun onWifiOnlyChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWifiOnly(enabled) }
    }

    fun onDeleteArchivesChange(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDeleteArchivesAfterInstall(enabled) }
    }

    /** Saves (or, when blank, removes) the SteamGridDB key and fetches the missing covers. */
    fun onSaveSteamGridDbKey(key: String) {
        viewModelScope.launch {
            artworkSettings.setApiKey(key)
            if (key.isNotBlank()) {
                artworkResolver.retryMissing()
                artworkScheduler.restart()
            }
        }
    }

    /** Every appearance option goes through here: saved, then applied app-wide. */
    fun onThemeChange(change: (ThemeSettings) -> ThemeSettings) {
        val next = change(uiState.value.settings.theme)
        // Applied at once so the screen recolors immediately, not after the disk write.
        ActiveTheme.settings = next
        viewModelScope.launch { settingsRepository.setTheme(next) }
    }

    /** Cycles System → Français → English; a gamepad-friendly alternative to a dropdown. */
    fun onCycleLanguage() {
        val next = AppLanguage.entries[(language.value.ordinal + 1) % AppLanguage.entries.size]
        language.value = next
        languageController.set(next)
    }
}
