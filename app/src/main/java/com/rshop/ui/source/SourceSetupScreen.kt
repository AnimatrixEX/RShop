package com.rshop.ui.source

import com.rshop.ui.util.relativeTime
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import com.rshop.ui.components.ControllerTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.rshop.R
import com.rshop.data.source.SyncSpeed
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.components.GameCard
import com.rshop.ui.components.SectionHeader
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.sourceErrorText

@Composable
fun SourceSetupScreen(
    onBack: () -> Unit,
    viewModel: SourceSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<SourceItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importConfig)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportConfig)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(
            when (message) {
                SourceMessage.Saved -> context.getString(R.string.source_saved)
                is SourceMessage.Removed -> context.getString(R.string.source_removed, message.name)
                SourceMessage.Exported -> context.getString(R.string.source_exported)
                is SourceMessage.ImportFailed -> context.getString(R.string.source_import_error, message.detail)
            },
        )
        viewModel.onMessageShown()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .widthIn(max = 980.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.padding(horizontal = Dimens.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                    FocusableSurface(
                        onClick = onBack,
                        shape = CircleShape,
                        containerColor = RShopColors.SurfaceHigh,
                        focusedContainerColor = RShopColors.SurfaceHighest,
                        glow = false,
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.source_setup_title), style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Text(
                    text = stringResource(R.string.source_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RShopColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
                )
            }
            if (sources.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.source_none_yet),
                        style = MaterialTheme.typography.bodyLarge,
                        color = RShopColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
                    )
                }
            }
            items(sources, key = { it.config.id }) { source ->
                SourceCard(
                    source = source,
                    onSync = { viewModel.syncSource(source.config.id) },
                    onRescan = { viewModel.syncSource(source.config.id, full = true) },
                    onSpeed = { viewModel.cycleSpeed(source.config.id) },
                    onStop = { viewModel.stopSync(source.config.id) },
                    onExport = {
                        viewModel.onExportRequested(source.config.id)
                        exportLauncher.launch("${source.config.id}.json")
                    },
                    onRemove = { confirmRemove = source },
                )
            }
            item { SectionHeader(stringResource(R.string.source_add_title)) }
            item {
                UrlForm(
                    url = state.url,
                    onUrlChange = viewModel::onUrlChange,
                    analyzing = state.analysis == AnalysisState.Running,
                    onAnalyze = viewModel::analyze,
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
            }
            item { AnalysisResult(state.analysis, busy = state.busy, onUse = viewModel::useAnalyzedSource) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }

    confirmRemove?.let { source ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.source_remove_title, source.config.name)) },
            text = { Text(pluralStringResource(R.plurals.source_remove_body, source.games, source.games)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    viewModel.removeSource(source.config.id)
                }) { Text(stringResource(R.string.source_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A configured source: what it holds, how its last sync went, and its actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
    source: SourceItem,
    onSync: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onRescan: () -> Unit,
    onSpeed: () -> Unit,
    onRemove: () -> Unit,
) {
    val sync = source.sync
    Column(
        Modifier
            .padding(horizontal = Dimens.ScreenPadding)
            .fillMaxWidth()
            .background(RShopColors.Surface, RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Text(source.config.name, style = MaterialTheme.typography.titleLarge)
        Text(source.config.baseUrl, style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        val (line, color) = when {
            sync.running -> pluralStringResource(R.plurals.sync_progress_games, sync.games, sync.games) to RShopColors.AccentBright
            sync.lastError != null -> stringResource(R.string.sync_failed, sourceErrorText(sync.lastError)) to RShopColors.Warning
            sync.lastSuccessAt != null -> stringResource(R.string.source_status, pluralStringResource(R.plurals.source_games, source.games, source.games), relativeTime(sync.lastSuccessAt)) to RShopColors.TextSecondary
            else -> pluralStringResource(R.plurals.source_games, source.games, source.games) to RShopColors.TextSecondary
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sync.running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(line, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sync.running) {
                ConsoleButton(stringResource(R.string.source_stop), onClick = onStop, style = ConsoleButtonStyle.Secondary)
            } else {
                ConsoleButton(stringResource(R.string.source_sync), onClick = onSync)
                ConsoleButton(stringResource(R.string.source_rescan), onClick = onRescan, style = ConsoleButtonStyle.Secondary)
            }
            val speed = SyncSpeed.of(source.config.minRequestIntervalMs)
            ConsoleButton(
                stringResource(
                    R.string.source_speed,
                    stringResource(
                        when (speed) {
                            SyncSpeed.Careful -> R.string.source_speed_careful
                            SyncSpeed.Normal -> R.string.source_speed_normal
                            SyncSpeed.Fast -> R.string.source_speed_fast
                        },
                    ),
                ),
                onClick = onSpeed,
                style = ConsoleButtonStyle.Secondary,
            )
            ConsoleButton(stringResource(R.string.source_export_short), onClick = onExport, style = ConsoleButtonStyle.Secondary)
            ConsoleButton(stringResource(R.string.source_remove_short), onClick = onRemove, style = ConsoleButtonStyle.Secondary)
        }
    }
}

@Composable
private fun UrlForm(
    url: String,
    onUrlChange: (String) -> Unit,
    analyzing: Boolean,
    onAnalyze: () -> Unit,
    onImport: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControllerTextField(shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { fieldModifier ->
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = fieldModifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.source_url_label)) },
                    placeholder = { Text(stringResource(R.string.source_url_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        // Close the (often fullscreen) keyboard so the analysis result is visible.
                        focusManager.clearFocus()
                        onAnalyze()
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RShopColors.Focus,
                        unfocusedBorderColor = RShopColors.Outline,
                        focusedContainerColor = RShopColors.SurfaceHigh,
                        unfocusedContainerColor = RShopColors.Surface,
                    ),
                )
            }
            Spacer(Modifier.width(12.dp))
            if (analyzing) {
                CircularProgressIndicator(Modifier.size(36.dp))
            } else {
                ConsoleButton(stringResource(R.string.source_analyze), onClick = {
                    focusManager.clearFocus()
                    onAnalyze()
                })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.source_url_help), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextTertiary)
        Spacer(Modifier.height(12.dp))
        ConsoleButton(stringResource(R.string.source_import), onClick = onImport, style = ConsoleButtonStyle.Secondary)
    }
}

@Composable
private fun AnalysisResult(state: AnalysisState, busy: Boolean, onUse: () -> Unit) {
    when (state) {
        AnalysisState.Idle -> Unit
        AnalysisState.Running -> Text(
            stringResource(R.string.source_analyzing),
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            color = RShopColors.TextSecondary,
        )
        AnalysisState.InvalidUrl -> Text(
            stringResource(R.string.source_invalid_url),
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            color = RShopColors.Warning,
        )
        is AnalysisState.Failed -> Text(
            stringResource(R.string.source_analysis_failed, sourceErrorText(state.error)),
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            color = RShopColors.Warning,
        )
        is AnalysisState.Done -> Summary(state.summary, busy, onUse)
    }
}

@Composable
private fun Summary(summary: AnalysisSummary, busy: Boolean, onUse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.source_result_title))
        Column(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            Text(summary.config.name, style = MaterialTheme.typography.titleLarge)
            Text(
                pluralStringResource(R.plurals.source_result_games, summary.sampleGames.size, summary.sampleGames.size),
                color = RShopColors.TextSecondary,
            )
            if (summary.consoles.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${stringResource(R.string.source_found_consoles)} : ${summary.consoles.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Finding(R.string.source_found_pagination, summary.pagination)
                Finding(R.string.source_found_search, summary.search)
                Finding(R.string.source_found_covers, summary.covers)
                Finding(R.string.source_found_platform, summary.platform)
                Finding(R.string.source_found_description, summary.description)
                Finding(R.string.source_found_screenshots, summary.screenshots)
                Finding(R.string.source_found_downloads, summary.downloads)
                Finding(R.string.source_found_hash, summary.hash)
                Finding(R.string.source_found_download_count, summary.downloadCount)
            }
            summary.detailsError?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.source_details_failed, it), color = RShopColors.Warning, style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            items(summary.sampleGames, key = { it.id }) { game ->
                GameCard(game = game, onClick = {}, modifier = Modifier.width(Dimens.CardWidth))
            }
        }
        Box(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            if (busy) CircularProgressIndicator() else ConsoleButton(stringResource(R.string.source_use), onClick = onUse)
        }
    }
}

@Composable
private fun Finding(label: Int, found: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (found) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = stringResource(if (found) R.string.yes else R.string.no),
            tint = if (found) RShopColors.Success else RShopColors.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = if (found) RShopColors.TextPrimary else RShopColors.TextTertiary)
    }
}
