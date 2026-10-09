package com.rshop.ui.details

import com.rshop.domain.model.sourceId
import com.rshop.data.source.SourceRepository
import com.rshop.data.source.displayNames
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rshop.data.artwork.ArtworkResolver
import com.rshop.data.repository.LibraryRepository
import com.rshop.data.storage.GamesDirectoryManager
import com.rshop.data.sync.CatalogSyncer
import com.rshop.data.sync.SourceError
import com.rshop.data.sync.toSourceError
import com.rshop.domain.model.DownloadError
import com.rshop.domain.model.DownloadTask
import com.rshop.domain.model.Game
import com.rshop.domain.model.InstalledGame
import com.rshop.domain.repository.GameRepository
import com.rshop.download.DownloadManager
import com.rshop.download.StartResult
import com.rshop.navigation.GameDetailsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

sealed interface GameDetailsUiState {
    data object Loading : GameDetailsUiState
    data object NotFound : GameDetailsUiState
    data class Loaded(
        val game: Game,
        val isFavorite: Boolean,
        val installed: InstalledGame?,
        val download: DownloadTask?,
        /** The game page is being read from the source (description, files, hash). */
        val refreshing: Boolean,
        val refreshError: SourceError?,
        /**
         * The site page to open in the device browser, for a file behind a page RShop cannot
         * fetch on its own (bot checks, logins); null when the file downloads directly.
         */
        val browserUrl: String?,
        /** Name of the source the game comes from, when several sources are configured. */
        val sourceName: String? = null,
    ) : GameDetailsUiState
}

sealed interface DetailsEvent {
    /** No games folder yet: the screen opens the folder picker, then installation continues. */
    data object PickFolder : DetailsEvent
    data class StartFailed(val error: DownloadError) : DetailsEvent
    /** The file is behind the site's page: the user gets it in the in-app browser. */
    data class OpenBrowser(val gameId: String, val url: String) : DetailsEvent
    data class Deleted(val title: String) : DetailsEvent
    data class DeleteFailed(val title: String) : DetailsEvent
}

@HiltViewModel
class GameDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GameRepository,
    private val library: LibraryRepository,
    private val downloads: DownloadManager,
    private val syncer: CatalogSyncer,
    private val directoryManager: GamesDirectoryManager,
    private val clock: Clock,
    private val artwork: ArtworkResolver,
    private val sources: SourceRepository,
) : ViewModel() {

    private val gameId = savedStateHandle.toRoute<GameDetailsRoute>().gameId
    private val refreshing = MutableStateFlow(false)
    private val refreshError = MutableStateFlow<SourceError?>(null)

    private val _events = MutableStateFlow<DetailsEvent?>(null)
    val events: StateFlow<DetailsEvent?> = _events

    val uiState: StateFlow<GameDetailsUiState> = combine(
        combine(repository.observeGame(gameId), repository.observeIsFavorite(gameId), sources.configs, ::Triple),
        library.observeInstalled(gameId),
        downloads.observeTask(gameId),
        refreshing,
        refreshError,
    ) { (game, isFavorite, configs), installed, download, isRefreshing, error ->
        if (game == null) {
            GameDetailsUiState.NotFound
        } else {
            val viaPage = game.downloadViaPage || game.downloadOptions.any { it.viaPage }
            val browserUrl = if (viaPage) {
                game.downloadOptions.firstOrNull { it.viaPage }?.url ?: game.downloadUrl ?: game.sourceUrl
            } else {
                null
            }
            val sourceName = configs.takeIf { it.size > 1 }?.displayNames()?.get(game.sourceId)
            GameDetailsUiState.Loaded(game, isFavorite, installed, download, isRefreshing, error, browserUrl, sourceName)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameDetailsUiState.Loading)

    init {
        viewModelScope.launch {
            val game = repository.observeGame(gameId).first() ?: return@launch
            // History rows reference the games table: only log games that exist.
            repository.recordView(gameId)
            launch { artwork.resolveNow(gameId) }
            val stale = game.detailsSyncedAt == null ||
                Duration.between(game.detailsSyncedAt, clock.instant()) > DETAILS_MAX_AGE
            if (stale) refreshDetails()
        }
    }

    fun refreshDetails() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            refreshError.value = null
            try {
                syncer.refreshDetails(gameId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Game page refresh failed for %s", gameId)
                refreshError.value = e.toSourceError()
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Files chosen when the games folder had to be picked first, to continue with the same ones. */
    private var pendingOption: String? = null
    private var pendingMore: List<String> = emptyList()

    /** [moreUrls]: further files of the game (other discs, bin + cue) installed after [optionUrl]. */
    fun onInstall(optionUrl: String? = null, moreUrls: List<String> = emptyList()) {
        pendingOption = optionUrl
        pendingMore = moreUrls
        viewModelScope.launch {
            when (val result = downloads.start(gameId, optionUrl, moreUrls)) {
                StartResult.Started -> Unit
                StartResult.NoGamesDirectory -> _events.value = DetailsEvent.PickFolder
                is StartResult.Failed -> _events.value = DetailsEvent.StartFailed(result.error)
                is StartResult.OpenInBrowser -> _events.value = DetailsEvent.OpenBrowser(gameId, result.url)
            }
        }
    }

    /** Installs a file the user downloaded themselves (e.g. in the device browser). */
    fun onInstallLocalFile(uri: Uri, displayName: String?, sizeBytes: Long?) {
        pendingLocalFile = Triple(uri, displayName, sizeBytes)
        viewModelScope.launch {
            when (val result = downloads.installLocalFile(gameId, uri, displayName, sizeBytes)) {
                StartResult.Started -> Unit
                StartResult.NoGamesDirectory -> _events.value = DetailsEvent.PickFolder
                is StartResult.Failed -> _events.value = DetailsEvent.StartFailed(result.error)
                is StartResult.OpenInBrowser -> Unit
            }
        }
    }

    private var pendingLocalFile: Triple<Uri, String?, Long?>? = null

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            if (directoryManager.select(uri).isSuccess) {
                pendingLocalFile?.let { (file, name, size) ->
                    pendingLocalFile = null
                    onInstallLocalFile(file, name, size)
                } ?: onInstall(pendingOption, pendingMore)
            }
        }
    }

    fun onPause() = viewModelScope.launch { downloads.pause(gameId) }

    fun onResume() = viewModelScope.launch { downloads.resume(gameId) }

    fun onCancel() = viewModelScope.launch { downloads.cancel(gameId) }

    fun onUninstall() {
        viewModelScope.launch {
            val title = (uiState.value as? GameDetailsUiState.Loaded)?.game?.title.orEmpty()
            _events.value = if (library.uninstall(gameId)) {
                downloads.cancel(gameId)
                DetailsEvent.Deleted(title)
            } else {
                DetailsEvent.DeleteFailed(title)
            }
        }
    }

    fun onToggleFavorite() {
        val current = uiState.value as? GameDetailsUiState.Loaded ?: return
        viewModelScope.launch { repository.setFavorite(gameId, !current.isFavorite) }
    }

    fun onEventHandled() {
        _events.value = null
    }

    private companion object {
        val DETAILS_MAX_AGE: Duration = Duration.ofHours(24)
    }
}
