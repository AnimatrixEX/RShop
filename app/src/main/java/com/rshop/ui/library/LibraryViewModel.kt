package com.rshop.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.data.repository.LibraryRepository
import com.rshop.domain.model.DownloadError
import com.rshop.domain.model.InstalledGame
import com.rshop.download.DownloadManager
import com.rshop.download.StartResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val loading: Boolean = true,
    val games: List<InstalledGame> = emptyList(),
    val platforms: List<String> = emptyList(),
    val platform: String? = null,
    val totalCount: Int = 0,
)

/** The game whose action sheet is open, with a lazily checked "files still on disk" flag. */
data class LibrarySelection(
    val game: InstalledGame,
    val filesPresent: Boolean? = null,
    val confirmDelete: Boolean = false,
)

sealed interface LibraryEvent {
    data class Deleted(val title: String) : LibraryEvent
    data class DeleteFailed(val title: String) : LibraryEvent
    data class UpdateFailed(val error: DownloadError?) : LibraryEvent
    data object UpdateStarted : LibraryEvent
    data class OpenBrowser(val gameId: String, val url: String) : LibraryEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val downloads: DownloadManager,
) : ViewModel() {

    private val platformFilter = MutableStateFlow<String?>(null)
    private val _selection = MutableStateFlow<LibrarySelection?>(null)
    val selection: StateFlow<LibrarySelection?> = _selection.asStateFlow()
    private val _events = MutableStateFlow<LibraryEvent?>(null)
    val events: StateFlow<LibraryEvent?> = _events.asStateFlow()

    val uiState: StateFlow<LibraryUiState> = combine(library.observeInstalled(), platformFilter) { games, platform ->
        val platforms = games.mapNotNull { it.platform }.distinct().sortedBy { it.lowercase() }
        val active = platform?.takeIf { it in platforms }
        LibraryUiState(
            loading = false,
            games = if (active == null) games else games.filter { it.platform == active },
            platforms = platforms,
            platform = active,
            totalCount = games.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun onPlatformSelected(platform: String?) {
        platformFilter.value = platform
    }

    fun onSelect(game: InstalledGame) {
        _selection.value = LibrarySelection(game)
        viewModelScope.launch {
            val present = library.filesPresent(game.gameId)
            _selection.update { current -> if (current?.game?.gameId == game.gameId) current.copy(filesPresent = present) else current }
        }
    }

    fun onDismiss() {
        _selection.value = null
    }

    fun onAskDelete() = _selection.update { it?.copy(confirmDelete = true) }

    fun onConfirmDelete() {
        val selection = _selection.value ?: return
        val selected = selection.game
        _selection.value = null
        viewModelScope.launch {
            val ok = if (selection.filesPresent == false) {
                library.forget(selected.gameId)
                true
            } else {
                library.uninstall(selected.gameId)
            }
            if (ok) downloads.cancel(selected.gameId)
            _events.value = if (ok) LibraryEvent.Deleted(selected.title) else LibraryEvent.DeleteFailed(selected.title)
        }
    }

    /** For entries whose files were removed outside the app. */
    fun onForget() {
        val selected = _selection.value?.game ?: return
        _selection.value = null
        viewModelScope.launch {
            library.forget(selected.gameId)
            downloads.cancel(selected.gameId)
            _events.value = LibraryEvent.Deleted(selected.title)
        }
    }

    fun onUpdate() {
        val selected = _selection.value?.game ?: return
        _selection.value = null
        viewModelScope.launch {
            _events.value = when (val result = downloads.start(selected.gameId)) {
                StartResult.Started -> LibraryEvent.UpdateStarted
                StartResult.NoGamesDirectory -> LibraryEvent.UpdateFailed(null)
                is StartResult.Failed -> LibraryEvent.UpdateFailed(result.error)
                is StartResult.OpenInBrowser -> LibraryEvent.OpenBrowser(selected.gameId, result.url)
            }
        }
    }

    fun onEventHandled() {
        _events.value = null
    }
}
