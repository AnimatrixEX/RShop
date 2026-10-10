package com.rshop.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.data.repository.LibraryRepository
import com.rshop.domain.model.DownloadError
import com.rshop.domain.model.InstalledGame
import com.rshop.domain.model.LibrarySort
import com.rshop.data.repository.FolderSpace
import com.rshop.data.storage.GamesFolder
import com.rshop.installation.DeviceSpace
import com.rshop.download.DownloadManager
import com.rshop.download.StartResult
import com.rshop.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

/** What the installed games take: in total, and in each games folder (per console, biggest first, with the volume around). */
data class StorageUsage(
    val gamesBytes: Long = 0,
    val folders: List<FolderUsage> = emptyList(),
    /** Consoles by size over every folder: the same console keeps the same color in each. */
    val platformOrder: List<String> = emptyList(),
)

data class FolderUsage(
    /** Null for the games whose folder was removed or is not one of the added folders. */
    val folder: GamesFolder?,
    val gamesBytes: Long,
    val byPlatform: List<PlatformUsage>,
    val device: DeviceSpace?,
    /** What the games of every folder on the same volume take: the volume bar is shared between them. */
    val volumeGamesBytes: Long,
)

/** Where each game is and how much room each folder's volume has; refreshed when installs or folders change. */
private data class Placement(val owners: Map<String, GamesFolder?> = emptyMap(), val spaces: List<FolderSpace> = emptyList())

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
    private val settings: SettingsRepository,
) : ViewModel() {

    /** The storage card is folded to its compact form; remembered across launches. */
    val storageCollapsed: StateFlow<Boolean> = settings.settings.map { it.storageCollapsed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onToggleStorage() {
        viewModelScope.launch { settings.setStorageCollapsed(!storageCollapsed.value) }
    }

    private val platformFilter = MutableStateFlow<String?>(null)
    private val sort = MutableStateFlow(LibrarySort.Title)
    private val _selection = MutableStateFlow<LibrarySelection?>(null)
    val selection: StateFlow<LibrarySelection?> = _selection.asStateFlow()
    private val _events = MutableStateFlow<LibraryEvent?>(null)
    val events: StateFlow<LibraryEvent?> = _events.asStateFlow()

    private val placement = MutableStateFlow(Placement())

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
        // The volumes' free space changes whenever a game is installed or removed, or a folder is added or taken away.
        viewModelScope.launch {
            combine(library.observeInstalled(), library.observeFolders()) { games, _ -> games }.collect { games ->
                placement.value = Placement(library.foldersOf(games), library.folderSpaces())
            }
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(library.observeInstalled(), platformFilter, placement, sort) { games, platform, where, order ->
        val platforms = games.mapNotNull { it.platform }.distinct().sortedBy { it.lowercase() }
        val active = platform?.takeIf { it in platforms }
        LibraryUiState(
            loading = false,
            games = order.apply(if (active == null) games else games.filter { it.platform == active }),
            platforms = platforms,
            platform = active,
            sort = order,
            totalCount = games.size,
            storage = storageUsage(games, where.owners, where.spaces),
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

internal fun storageUsage(
    games: List<InstalledGame>,
    owners: Map<String, GamesFolder?> = emptyMap(),
    spaces: List<FolderSpace> = emptyList(),
): StorageUsage {
    val sized = games.filter { (it.sizeOnDisk ?: 0) > 0 }
    fun byPlatform(list: List<InstalledGame>) = list.groupBy { it.platform ?: "?" }
        .map { (platform, items) -> PlatformUsage(platform, items.sumOf { it.sizeOnDisk ?: 0 }, items.size) }
        .sortedByDescending { it.bytes }

    // Folders to show: every usable one (an empty one still has room to tell about), plus the ones that hold games.
    val known = LinkedHashMap<String, Pair<GamesFolder, DeviceSpace?>>()
    spaces.forEach { known[it.folder.uri.toString()] = it.folder to it.device }
    owners.values.filterNotNull().forEach { known.getOrPut(it.uri.toString()) { it to null } }
    val gamesByFolder = sized.groupBy { owners[it.gameId]?.uri?.toString() }

    val rows = known.entries
        .sortedByDescending { it.value.first.isDefault }
        .map { (key, value) ->
            val (folder, device) = value
            val inFolder = gamesByFolder[key].orEmpty()
            FolderUsage(folder, inFolder.sumOf { it.sizeOnDisk ?: 0 }, byPlatform(inFolder), device, volumeGamesBytes = 0)
        }
        .let { list ->
            // Folders on one volume share its bar: it shows what all their games take.
            val perVolume = list.groupBy { it.folder?.location?.volume }
                .mapValues { (_, items) -> items.sumOf { it.gamesBytes } }
            list.map { it.copy(volumeGamesBytes = perVolume[it.folder?.location?.volume] ?: it.gamesBytes) }
        }
    val unplaced = gamesByFolder.filterKeys { it == null || it !in known }.values.flatten()
    val all = if (unplaced.isEmpty()) rows else rows + FolderUsage(null, unplaced.sumOf { it.sizeOnDisk ?: 0 }, byPlatform(unplaced), null, 0)

    return StorageUsage(
        gamesBytes = sized.sumOf { it.sizeOnDisk ?: 0 },
        folders = all,
        platformOrder = byPlatform(sized).map { it.platform },
    )
}
