package com.rshop.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.data.repository.GameListRepository
import com.rshop.domain.model.Game
import com.rshop.domain.model.GameList
import com.rshop.domain.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val loading: Boolean = true,
    val lists: List<GameList> = emptyList(),
    /** The custom list on screen, or null for the built-in favorites. */
    val selected: GameList? = null,
    val favoritesCount: Int = 0,
    val games: List<Game> = emptyList(),
)

sealed interface FavoritesDialog {
    data object Create : FavoritesDialog
    data class Rename(val list: GameList) : FavoritesDialog
    data class ConfirmDelete(val list: GameList) : FavoritesDialog
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val games: GameRepository,
    private val listRepository: GameListRepository,
) : ViewModel() {

    private val selectedId = MutableStateFlow<Long?>(null)
    private val _dialog = MutableStateFlow<FavoritesDialog?>(null)
    val dialog: StateFlow<FavoritesDialog?> = _dialog.asStateFlow()

    private val shownGames = selectedId.flatMapLatest { id ->
        if (id == null) games.observeFavorites() else listRepository.observeGames(id)
    }

    val uiState: StateFlow<FavoritesUiState> = combine(
        listRepository.observeLists(),
        selectedId,
        games.observeFavorites(),
        shownGames,
    ) { lists, id, favorites, shown ->
        // A deleted list falls back to the favorites.
        val selected = lists.firstOrNull { it.id == id }
        FavoritesUiState(
            loading = false,
            lists = lists,
            selected = selected,
            favoritesCount = favorites.size,
            games = if (selected == null && id != null) favorites else shown,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    /** [listId] null: the favorites. */
    fun onSelect(listId: Long?) {
        selectedId.value = listId
    }

    fun onAskCreate() {
        _dialog.value = FavoritesDialog.Create
    }

    fun onAskRename() {
        uiState.value.selected?.let { _dialog.value = FavoritesDialog.Rename(it) }
    }

    fun onAskDelete() {
        uiState.value.selected?.let { _dialog.value = FavoritesDialog.ConfirmDelete(it) }
    }

    fun onDismissDialog() {
        _dialog.value = null
    }

    fun onCreate(name: String) {
        _dialog.value = null
        viewModelScope.launch { listRepository.create(name)?.let { selectedId.value = it } }
    }

    fun onRename(list: GameList, name: String) {
        _dialog.value = null
        viewModelScope.launch { listRepository.rename(list.id, name) }
    }

    fun onDelete(list: GameList) {
        _dialog.value = null
        selectedId.value = null
        viewModelScope.launch { listRepository.delete(list.id) }
    }
}
