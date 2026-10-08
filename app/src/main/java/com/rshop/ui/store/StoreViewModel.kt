package com.rshop.ui.store

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.rshop.data.source.SourceManager
import com.rshop.data.source.displayNames
import com.rshop.data.sync.SourceError
import com.rshop.data.sync.toSourceError
import com.rshop.domain.model.CatalogFilter
import com.rshop.domain.model.Game
import com.rshop.domain.model.SortOrder
import com.rshop.domain.repository.GameRepository
import com.rshop.navigation.StoreRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface RemoteSearchState {
    data object Idle : RemoteSearchState
    data object Running : RemoteSearchState
    data class Done(val query: String, val found: Int) : RemoteSearchState
    data class Failed(val error: SourceError) : RemoteSearchState
}

data class StoreUiState(
    val genre: String? = null,
    val platform: String? = null,
    val sort: SortOrder = SortOrder.Title,
    val genres: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    /** Source filter, shown only when several sources are configured: (id, name). */
    val sources: List<Pair<String, String>> = emptyList(),
    val sourceId: String? = null,
    val resultCount: Int = 0,
    val isLoading: Boolean = true,
    /** A configured site has its own search (used when the local catalogue lacks a game). */
    val canSearchRemote: Boolean = false,
    val remoteSearch: RemoteSearchState = RemoteSearchState.Idle,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class StoreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GameRepository,
    private val sourceManager: SourceManager,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<StoreRoute>()

    private val _query = MutableStateFlow("")

    /** Exposed on its own so the text field reads it synchronously (no cursor jumps). */
    val query: StateFlow<String> = _query.asStateFlow()

    private val options = MutableStateFlow(CatalogFilter(genre = route.genre, platform = route.platform))
    private val remoteSearch = MutableStateFlow<RemoteSearchState>(RemoteSearchState.Idle)

    private val filter: Flow<CatalogFilter> = combine(
        // Typing is debounced; clearing the field is applied immediately.
        _query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
        options,
    ) { q, f -> f.copy(query = q) }.distinctUntilChanged()

    /** Lazily loaded results: only the visible part of a large catalogue is read. */
    val games: Flow<PagingData<Game>> = filter
        .flatMapLatest { repository.pagedCatalog(it) }
        .cachedIn(viewModelScope)

    val uiState: StateFlow<StoreUiState> = combine(
        options,
        combine(repository.observeGenres(), repository.observePlatforms(), ::Pair),
        filter.flatMapLatest { repository.observeCatalogCount(it) },
        sourceManager.configs,
        remoteSearch,
    ) { f, (genres, platforms), count, configs, remote ->
        StoreUiState(
            genre = f.genre,
            platform = f.platform,
            sources = if (configs.size > 1) configs.displayNames().toList() else emptyList(),
            sourceId = f.sourceId?.takeIf { id -> configs.any { it.id == id } },
            sort = f.sort,
            genres = genres,
            platforms = platforms,
            resultCount = count,
            isLoading = false,
            canSearchRemote = configs.any { it.searchUrl != null },
            remoteSearch = remote,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoreUiState(genre = route.genre, platform = route.platform))

    fun onQueryChange(value: String) {
        _query.value = value
        if (remoteSearch.value !is RemoteSearchState.Running) remoteSearch.value = RemoteSearchState.Idle
    }

    fun onGenreSelected(genre: String?) {
        options.update { it.copy(genre = genre) }
    }

    fun onClearPlatform() {
        options.update { it.copy(platform = null) }
    }

    /** Console filter: essential once a source lists thousands of ROMs. */
    fun onPlatformSelected(platform: String?) {
        options.update { it.copy(platform = platform) }
    }

    fun onSourceSelected(sourceId: String?) {
        options.update { it.copy(sourceId = sourceId) }
    }

    fun onCycleSort() {
        options.update { it.copy(sort = SortOrder.entries[(it.sort.ordinal + 1) % SortOrder.entries.size]) }
    }

    /** Asks the site's search engine; hits land in the local catalogue and show up in the grid. */
    fun onSearchOnSite() {
        val q = _query.value.trim()
        if (q.isEmpty() || remoteSearch.value is RemoteSearchState.Running) return
        remoteSearch.value = RemoteSearchState.Running
        viewModelScope.launch {
            remoteSearch.value = try {
                RemoteSearchState.Done(q, sourceManager.searchRemote(q) ?: 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Remote search failed")
                RemoteSearchState.Failed(e.toSourceError())
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
