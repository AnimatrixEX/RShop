package com.rshop.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.data.sync.DetailsPrefetcher
import com.rshop.data.sync.SyncScheduler
import com.rshop.data.update.AppUpdater
import com.rshop.data.update.UpdateState
import com.rshop.domain.model.Game
import com.rshop.domain.repository.SettingsRepository
import com.rshop.ui.components.SettingsBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** What the whole window shares: reading the focused game ahead of time, and the update badge. */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val prefetcher: DetailsPrefetcher,
    private val updater: AppUpdater,
    private val settings: SettingsRepository,
    private val clock: Clock,
    syncScheduler: SyncScheduler,
) : ViewModel() {

    /** A newer version is known (or being installed). */
    private val updateAvailable = updater.state
        .map { it is UpdateState.Available || it is UpdateState.Downloading || it is UpdateState.Verifying || it is UpdateState.Installing }

    /**
     * The mark on the Settings button: a catalogue sync running, else a new RShop version found.
     * The sync shows first while it lasts: it is short, the new version stays.
     */
    val settingsBadge: StateFlow<SettingsBadge> = combine(
        syncScheduler.state.map { it.running }.distinctUntilChanged(),
        updateAvailable,
    ) { syncing, update ->
        when {
            syncing -> SettingsBadge.Syncing
            update -> SettingsBadge.Update
            else -> SettingsBadge.None
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsBadge.None)

    init {
        viewModelScope.launch {
            val current = settings.settings.first()
            if (!current.autoCheckUpdates) return@launch
            val now = clock.millis()
            if (now - current.lastUpdateCheckAt < TimeUnit.HOURS.toMillis(CHECK_EVERY_HOURS)) return@launch
            settings.setLastUpdateCheckAt(now)
            updater.check(quiet = true)
        }
    }

    /** Only when the player chose to read pages ahead (a request per card the focus rests on is not free). */
    private val readAhead: StateFlow<Boolean> = settings.settings
        .map { it.readPagesAhead }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun prefetch(game: Game) {
        if (readAhead.value) prefetcher.request(game)
    }

    private companion object {
        const val CHECK_EVERY_HOURS = 24L
    }
}
