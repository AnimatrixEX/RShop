package com.rshop.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Ids of the installed games, read by every game card to show its "installed" mark. */
val LocalInstalledIds = compositionLocalOf<Set<String>> { emptySet() }

@HiltViewModel
class InstalledIdsViewModel @Inject constructor(library: LibraryRepository) : ViewModel() {
    val ids: StateFlow<Set<String>> = library.observeInstalledIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}
