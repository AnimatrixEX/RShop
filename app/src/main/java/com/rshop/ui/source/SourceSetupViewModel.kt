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
import com.rshop.scraper.model.CatalogSection
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
    /** The consoles with their pages, to choose which ones to read. */
    val sections: List<CatalogSection>,
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
    data object ConsolesSaved : SourceMessage
    data class ImportFailed(val detail: String) : SourceMessage
}

data class SourceSetupUiState(
    val url: String = "",
    val analysis: AnalysisState = AnalysisState.Idle,
    val busy: Boolean = false,
    val message: SourceMessage? = null,
    /** Consoles chosen for the source being added; null reads them all. */
    val analysisConsoles: Set<String>? = null,
    val picker: ConsolePickerState? = null,
)

@HiltViewModel
class SourceSetupViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val documents: TextDocuments,
    private val scheduler: SyncScheduler,
    games: GameRepository,
) : ViewModel() {

    val syncPaused: StateFlow<Boolean> = scheduler.paused.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setSyncPaused(paused: Boolean) {
        viewModelScope.launch { scheduler.setPaused(paused) }
    }

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
        _state.update { it.copy(analysis = AnalysisState.Running, analysisConsoles = null) }
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
        val chosen = _state.value.analysisConsoles
        val config = if (chosen == null) summary.config else summary.config.copy(enabledSections = summary.sections.map { it.url }.filter { it in chosen })
        runBusy {
            sourceManager.add(config)
            _state.update { it.copy(url = "", analysis = AnalysisState.Idle, analysisConsoles = null, message = SourceMessage.Saved) }
        }
    }

    // --- Console choice -----------------------------------------------------------------

    private var pickerJob: Job? = null

    /** Opens the console choice of a source already added (the site's console list is read again). */
    fun openConsolePicker(sourceId: String) {
        val item = sources.value.firstOrNull { it.config.id == sourceId } ?: return
        loadConsoles(ConsolePickerTarget.Source(sourceId, item.config.name))
    }

    /** Opens the console choice for the site being analysed (its consoles are already known). */
    fun openAnalysisConsolePicker() {
        val summary = (_state.value.analysis as? AnalysisState.Done)?.summary ?: return
        val selected = _state.value.analysisConsoles ?: summary.sections.map { it.url }.toSet()
        _state.update { it.copy(picker = ConsolePickerState(ConsolePickerTarget.Analysis, sections = summary.sections, selected = selected)) }
    }

    private fun loadConsoles(target: ConsolePickerTarget.Source) {
        pickerJob?.cancel()
        _state.update { it.copy(picker = ConsolePickerState(target, loading = true)) }
        pickerJob = viewModelScope.launch {
            try {
                val sections = sourceManager.listConsoles(target.id)
                val enabled = sources.value.firstOrNull { it.config.id == target.id }?.config?.enabledSections
                // A console the site has since removed is not shown; the others keep the saved choice.
                val selected = sections.map { it.url }.filter { enabled == null || it in enabled }.toSet()
                _state.update { it.copy(picker = ConsolePickerState(target, sections = sections, selected = selected)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Cannot list the consoles of %s", target.id)
                _state.update { it.copy(picker = ConsolePickerState(target, error = e.toSourceError())) }
            }
        }
    }

    fun retryConsoles() {
        (_state.value.picker?.target as? ConsolePickerTarget.Source)?.let(::loadConsoles)
    }

    fun toggleConsole(url: String) = updatePicker { picker ->
        picker.copy(selected = if (url in picker.selected) picker.selected - url else picker.selected + url)
    }

    fun selectAllConsoles() = updatePicker { it.copy(selected = it.sections.map { s -> s.url }.toSet()) }

    fun selectNoConsole() = updatePicker { it.copy(selected = emptySet()) }

    private fun updatePicker(change: (ConsolePickerState) -> ConsolePickerState) =
        _state.update { state -> state.copy(picker = state.picker?.let(change)) }

    fun dismissConsolePicker() {
        pickerJob?.cancel()
        _state.update { it.copy(picker = null) }
    }

    fun applyConsoles() {
        val picker = _state.value.picker ?: return
        if (picker.selected.isEmpty() || picker.loading) return
        _state.update { it.copy(picker = null) }
        when (val target = picker.target) {
            ConsolePickerTarget.Analysis -> {
                val all = picker.sections.size == picker.selected.size
                _state.update { it.copy(analysisConsoles = if (all) null else picker.selected) }
            }
            is ConsolePickerTarget.Source -> runBusy {
                try {
                    sourceManager.setConsoles(target.id, picker.sections, picker.selected)
                    _state.update { it.copy(message = SourceMessage.ConsolesSaved) }
                } catch (e: IOException) {
                    _state.update { it.copy(message = SourceMessage.ImportFailed(e.message.orEmpty())) }
                }
            }
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

    fun syncSource(sourceId: String, full: Boolean = false) {
        viewModelScope.launch { sourceManager.syncNow(sourceId, full) }
    }

    fun cycleSpeed(sourceId: String) {
        viewModelScope.launch { sourceManager.cycleSpeed(sourceId) }
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
            sections = sections,
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
