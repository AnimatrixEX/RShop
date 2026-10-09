package com.rshop.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.data.repository.LibraryRepository
import com.rshop.domain.model.DownloadError
import com.rshop.domain.model.InstalledGame
import com.rshop.domain.model.LibrarySort
import com.rshop.installation.DeviceSpace
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
    val sort: LibrarySort = LibrarySort.Title,
    val totalCount: Int = 0,
    val storage: StorageUsage = StorageUsage(),
)

/** What the installed games take: in total, per console (biggest first), and the volume around them. */
data class StorageUsage(
    val gamesBytes: Long = 0,
    val byPlatform: List<PlatformUsage> = emptyList(),
    val device: DeviceSpace? = null,
)

data class PlatformUsage(val platform: String, val bytes: Long, val games: Int)

/** The game whose action sheet is open, with a lazily checked "files still on disk" flag. */
data class LibrarySelection(
    val game: InstalledGame,
    val filesPresent: Boolean? = null,
    val confirmDelete: Boolean = false,
    /** Format of the installed files, read from them when it was never stored. */
    val format: String? = null,
)

sealed interface LibraryEvent {
    data class Deleted(val title: String) : LibraryEvent
    data class DeleteFailed(val title: String) : LibraryEvent
    data class UpdateFailed(val error: DownloadError?) : LibraryEvent
    data object UpdateStarted : LibraryEvent
    data class Scanned(val found: Int) : LibraryEvent
    data class OpenBrowser(val gameId: String, val url: String) : LibraryEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val downloads: DownloadManager,
) : ViewModel() {

    private val platformFilter = MutableStateFlow<String?>(null)
    private val sort = MutableStateFlow(LibrarySort.Title)
    private val _selection = MutableStateFlow<LibrarySelection?>(null)
    val selection: StateFlow<LibrarySelection?> = _selection.asStateFlow()
    private val _events = MutableStateFlow<LibraryEvent?>(null)
    val events: StateFlow<LibraryEvent?> = _events.asStateFlow()

    private val device = MutableStateFlow<DeviceSpace?>(null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Looks in the games folder for games RShop did not install. */
    fun onScan() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch {
            val found = try {
                library.scanInstalled()
            } finally {
                _scanning.value = false
            }
            _events.value = LibraryEvent.Scanned(found)
        }
    }

    init {
        // The volume's free space changes whenever a game is installed or removed.
        viewModelScope.launch { library.observeInstalled().collect { device.value = library.deviceSpace() } }
    }

    val uiState: StateFlow<LibraryUiState> = combine(library.observeInstalled(), platformFilter, device, sort) { games, platform, deviceSpace, order ->
        val platforms = games.mapNotNull { it.platform }.distinct().sortedBy { it.lowercase() }
        val active = platform?.takeIf { it in platforms }
        LibraryUiState(
            loading = false,
            games = order.apply(if (active == null) games else games.filter { it.platform == active }),
            platforms = platforms,
            platform = active,
            sort = order,
            totalCount = games.size,
            storage = storageUsage(games, deviceSpace),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun onCycleSort() {
        sort.value = sort.value.next()
    }

    fun onPlatformSelected(platform: String?) {
        platformFilter.value = platform
    }

    fun onSelect(game: InstalledGame) {
        _selection.value = LibrarySelection(game)
        viewModelScope.launch {
            val present = library.filesPresent(game.gameId)
            _selection.update { current -> if (current?.game?.gameId == game.gameId) current.copy(filesPresent = present) else current }
            val format = if (present) library.fileFormat(game.gameId) else null
            _selection.update { current -> if (current?.game?.gameId == game.gameId) current.copy(format = format) else current }
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

internal fun storageUsage(games: List<InstalledGame>, device: DeviceSpace?): StorageUsage {
    val sized = games.filter { (it.sizeOnDisk ?: 0) > 0 }
    val byPlatform = sized.groupBy { it.platform ?: "?" }
        .map { (platform, list) -> PlatformUsage(platform, list.sumOf { it.sizeOnDisk ?: 0 }, list.size) }
        .sortedByDescending { it.bytes }
    return StorageUsage(gamesBytes = byPlatform.sumOf { it.bytes }, byPlatform = byPlatform, device = device)
}
