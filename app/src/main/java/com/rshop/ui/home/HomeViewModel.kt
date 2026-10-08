package com.rshop.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.domain.model.Game
import com.rshop.domain.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    /** The "Featured" carousel: the most popular games. */
    val featured: List<Game> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val recentlyViewed: List<Game> = emptyList(),
    val favorites: List<Game> = emptyList(),
    val recentlyAdded: List<Game> = emptyList(),
    val popular: List<Game> = emptyList(),
    val recentlyUpdated: List<Game> = emptyList(),
    val genres: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
) {
    val isCatalogEmpty: Boolean get() = !isLoading && featured.isEmpty()
}

private data class Shelves(
    val recentlyAdded: List<Game>,
    val popular: List<Game>,
    val recentlyUpdated: List<Game>,
)

private data class Personal(
    val recentlyViewed: List<Game>,
    val favorites: List<Game>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: GameRepository,
) : ViewModel() {

    // The site's download counters drive popularity, so these are the most downloaded games.
    private val featured = repository.observePopular(FEATURED_COUNT)

    private val shelves = combine(
        repository.observeRecentlyAdded(SHELF_SIZE),
        repository.observePopular(SHELF_SIZE),
        repository.observeRecentlyUpdated(SHELF_SIZE),
        ::Shelves,
    )

    private val personal = combine(
        repository.observeRecentlyViewed(SHELF_SIZE),
        repository.observeFavorites(),
        ::Personal,
    )

    private val facets = combine(repository.observeGenres(), repository.observePlatforms(), ::Pair)

    val uiState: StateFlow<HomeUiState> = combine(
        featured,
        shelves,
        personal,
        facets,
    ) { featured, shelves, personal, (genres, platforms) ->
        HomeUiState(
            isLoading = false,
            featured = featured,
            favoriteIds = personal.favorites.mapTo(HashSet()) { it.id },
            recentlyViewed = personal.recentlyViewed,
            favorites = personal.favorites,
            recentlyAdded = shelves.recentlyAdded,
            popular = shelves.popular,
            recentlyUpdated = shelves.recentlyUpdated,
            genres = genres,
            platforms = platforms,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onToggleFavorite(game: Game, isFavorite: Boolean) {
        viewModelScope.launch { repository.setFavorite(game.id, !isFavorite) }
    }

    private companion object {
        const val SHELF_SIZE = 15
        const val FEATURED_COUNT = 5
    }
}
