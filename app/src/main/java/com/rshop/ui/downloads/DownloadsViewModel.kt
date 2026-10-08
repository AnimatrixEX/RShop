package com.rshop.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.domain.model.DownloadTask
import com.rshop.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val manager: DownloadManager,
) : ViewModel() {

    /** Null until the first database read, so the empty state never flashes. */
    val tasks: StateFlow<List<DownloadTask>?> =
        manager.observeTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pause(gameId: String) = viewModelScope.launch { manager.pause(gameId) }
    fun resume(gameId: String) = viewModelScope.launch { manager.resume(gameId) }
    fun cancel(gameId: String) = viewModelScope.launch { manager.cancel(gameId) }
    fun clearFinished() = viewModelScope.launch { manager.clearFinished() }
}
