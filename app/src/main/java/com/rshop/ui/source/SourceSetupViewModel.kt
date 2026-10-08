package com.rshop.ui.source

import kotlinx.coroutines.flow.combine
import com.rshop.domain.repository.GameRepository
import com.rshop.data.sync.SyncState
import com.rshop.data.sync.SyncScheduler
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rshop.data.source.SourceManager
import com.rshop.data.storage.TextDocuments
import com.rshop.data.sync.SourceError
import com.rshop.data.sync.toDomain
import com.rshop.data.sync.toSourceError
import com.rshop.domain.model.Game
import com.rshop.scraper.ScraperConfigException
import com.rshop.scraper.analysis.PaginationKind
import com.rshop.scraper.analysis.SiteAnalysis
import com.rshop.scraper.config.ScraperConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/** What the analysis found, as a checklist the user can judge before accepting the source. */
data class AnalysisSummary(
    val config: ScraperConfig,
    val sampleGames: List<Game>,
    val consoles: List<String>,
    val pagination: Boolean,
    val search: Boolean,
    val covers: Boolean,
    val description: Boolean,
    val screenshots: Boolean,
    val downloads: Boolean,
    val hash: Boolean,
    val platform: Boolean,
    val downloadCount: Boolean,
    val detailsError: String?,
)

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Running : AnalysisState
    data object InvalidUrl : AnalysisState
    data class Failed(val error: SourceError) : AnalysisState
    data class Done(val summary: AnalysisSummary) : AnalysisState
}

/** One configured source, as the list shows it. */
data class SourceItem(
    val config: ScraperConfig,
    val games: Int,
    val sync: SyncState,
)

sealed interface SourceMessage {
    data object Saved : SourceMessage
    data class Removed(val name: String) : SourceMessage
    data object Exported : SourceMessage
    data class ImportFailed(val detail: String) : SourceMessage
}

data class SourceSetupUiState(
    val url: String = "",
    val analysis: AnalysisState = AnalysisState.Idle,
    val busy: Boolean = false,
    val message: SourceMessage? = null,
)

@HiltViewModel
class SourceSetupViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val documents: TextDocuments,
    scheduler: SyncScheduler,
    games: GameRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceSetupUiState())
    val state: StateFlow<SourceSetupUiState> = _state.asStateFlow()

    val sources: StateFlow<List<SourceItem>> = combine(
        sourceManager.configs,
        games.observeCountsBySource(),
        scheduler.sourceStates,
    ) { configs, counts, states ->
        configs.map { SourceItem(it, counts[it.id] ?: 0, states[it.id] ?: SyncState()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Source whose config the document picker is exporting. */
    private var exportId: String? = null

    private var analysisJob: Job? = null

    fun onUrlChange(value: String) = _state.update { it.copy(url = value) }

    fun analyze() {
        val url = _state.value.url.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(analysis = AnalysisState.InvalidUrl) }
            return
        }
        analysisJob?.cancel()
        _state.update { it.copy(analysis = AnalysisState.Running) }
        analysisJob = viewModelScope.launch {
            val result = try {
                AnalysisState.Done(sourceManager.analyze(url).toSummary())
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Analysis rejected %s", url)
                if (e is ScraperConfigException) AnalysisState.Failed(e.toSourceError()) else AnalysisState.InvalidUrl
            } catch (e: Exception) {
                Timber.w(e, "Analysis failed for %s", url)
                AnalysisState.Failed(e.toSourceError())
            }
            _state.update { it.copy(analysis = result) }
        }
    }

    fun useAnalyzedSource() {
        val summary = (_state.value.analysis as? AnalysisState.Done)?.summary ?: return
        runBusy {
            sourceManager.add(summary.config)
            _state.update { it.copy(url = "", analysis = AnalysisState.Idle, message = SourceMessage.Saved) }
        }
    }

    fun importConfig(uri: Uri) = runBusy {
        try {
            sourceManager.importJson(documents.read(uri))
            _state.update { it.copy(message = SourceMessage.Saved) }
        } catch (e: ScraperConfigException) {
            _state.update { it.copy(message = SourceMessage.ImportFailed(e.problems.joinToString("; "))) }
        } catch (e: SerializationException) {
            _state.update { it.copy(message = SourceMessage.ImportFailed(e.message.orEmpty().take(200))) }
        } catch (e: IOException) {
            _state.update { it.copy(message = SourceMessage.ImportFailed(e.message.orEmpty())) }
        }
    }

    /** Remembers which source the save dialog about to open is for. */
    fun onExportRequested(sourceId: String) {
        exportId = sourceId
    }

    fun exportConfig(uri: Uri) = runBusy {
        val json = exportId?.let { sourceManager.exportJson(it) } ?: return@runBusy
        try {
            documents.write(uri, json)
            _state.update { it.copy(message = SourceMessage.Exported) }
        } catch (e: IOException) {
            _state.update { it.copy(message = SourceMessage.ImportFailed(e.message.orEmpty())) }
        }
    }

    fun removeSource(sourceId: String) = runBusy {
        val name = sources.value.firstOrNull { it.config.id == sourceId }?.config?.name ?: sourceId
        sourceManager.remove(sourceId)
        _state.update { it.copy(message = SourceMessage.Removed(name)) }
    }

    fun syncSource(sourceId: String) {
        viewModelScope.launch { sourceManager.syncNow(sourceId) }
    }

    fun stopSync(sourceId: String) = sourceManager.stopSync(sourceId)

    fun onMessageShown() = _state.update { it.copy(message = null) }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun SiteAnalysis.toSummary(): AnalysisSummary {
        val details = sampleDetails
        return AnalysisSummary(
            config = config,
            sampleGames = sampleGames.map { it.toDomain(config.id) },
            consoles = consoles,
            pagination = pagination != PaginationKind.None || config.sections != null,
            search = config.searchUrl != null,
            covers = sampleGames.any { it.coverUrl != null },
            description = details?.description != null,
            screenshots = details?.screenshots?.isNotEmpty() == true,
            downloads = details?.downloads?.isNotEmpty() == true,
            hash = details?.downloads?.firstOrNull()?.sha256 != null,
            platform = (details?.game?.platform ?: sampleGames.firstOrNull()?.platform) != null,
            downloadCount = sampleGames.any { it.downloadCount != null } || details?.game?.downloadCount != null,
            detailsError = detailsError,
        )
    }
}
