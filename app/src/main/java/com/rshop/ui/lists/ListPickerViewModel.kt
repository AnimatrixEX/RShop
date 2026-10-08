package com.rshop.ui.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rshop.data.repository.GameListRepository
import com.rshop.domain.model.GameList
import com.rshop.navigation.GameDetailsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Custom lists seen from a game page: which ones the game is in, and changing that. */
@HiltViewModel
class ListPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GameListRepository,
) : ViewModel() {

    private val gameId = savedStateHandle.toRoute<GameDetailsRoute>().gameId

    val lists: StateFlow<List<GameList>> = repository.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val memberOf: StateFlow<Set<Long>> = repository.observeListIdsOf(gameId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun onToggle(list: GameList) {
        viewModelScope.launch { repository.setMember(list.id, gameId, list.id !in memberOf.value) }
    }

    /** Creates the list and puts the game in it. */
    fun onCreate(name: String) {
        viewModelScope.launch { repository.create(name)?.let { repository.setMember(it, gameId, true) } }
    }
}
