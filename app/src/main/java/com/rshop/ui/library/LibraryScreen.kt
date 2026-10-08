package com.rshop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rshop.R
import com.rshop.domain.model.InstalledGame
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.ConsoleChip
import com.rshop.ui.components.EmptyState
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.components.GameCover
import com.rshop.ui.components.coverGame
import com.rshop.ui.components.ReturnFocus
import com.rshop.ui.components.rememberInitialFocusRequester
import com.rshop.ui.components.rememberReturnFocus
import com.rshop.ui.components.returnFocusTarget
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.downloadErrorMessage
import com.rshop.ui.util.formatDate
import com.rshop.ui.util.formatSize

/** Installed games, console-style: a grid of covers, filters per console, actions in a sheet. */
@Composable
fun LibraryScreen(
    onBrowseStore: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenBrowser: (gameId: String, url: String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Back from a game page or the browser, the card that opened it gets focus again.
    val returnFocus = rememberReturnFocus()

    LaunchedEffect(event) {
        val current = event ?: return@LaunchedEffect
        if (current is LibraryEvent.OpenBrowser) {
            viewModel.onEventHandled()
            returnFocus.onOpen(current.gameId)
            onOpenBrowser(current.gameId, current.url)
            return@LaunchedEffect
        }
        snackbar.showSnackbar(
            when (current) {
                is LibraryEvent.Deleted -> context.getString(R.string.library_deleted, current.title)
                is LibraryEvent.DeleteFailed -> context.getString(R.string.library_delete_failed, current.title)
                LibraryEvent.UpdateStarted, is LibraryEvent.OpenBrowser -> context.getString(R.string.download_status_queued)
                is LibraryEvent.UpdateFailed -> context.getString(
                    R.string.details_start_failed,
                    current.error?.let { context.downloadErrorMessage(it) } ?: context.getString(R.string.dl_error_no_directory),
                )
            },
        )
        viewModel.onEventHandled()
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> Unit
            state.totalCount == 0 -> EmptyLibrary(onBrowseStore)
            else -> LibraryGrid(state, returnFocus, viewModel::onPlatformSelected, viewModel::onSelect)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }

    selection?.let { current ->
        GameActionsSheet(
            selection = current,
            onDismiss = viewModel::onDismiss,
            onOpenPage = {
                viewModel.onDismiss()
                returnFocus.onOpen(current.game.gameId)
                onOpenGame(current.game.gameId)
            },
            onUpdate = viewModel::onUpdate,
            onAskDelete = viewModel::onAskDelete,
            onConfirmDelete = viewModel::onConfirmDelete,
            onForget = viewModel::onForget,
        )
    }
}

@Composable
private fun EmptyLibrary(onBrowseStore: () -> Unit) {
    val browseFocus = rememberInitialFocusRequester()
    EmptyState(
        icon = painterResource(R.drawable.ic_library),
        title = stringResource(R.string.library_empty_title),
        body = stringResource(R.string.library_empty_body),
        action = {
            ConsoleButton(
                text = stringResource(R.string.action_browse_store),
                onClick = onBrowseStore,
                modifier = Modifier.focusRequester(browseFocus),
            )
        },
    )
}

@Composable
private fun LibraryGrid(state: LibraryUiState, returnFocus: ReturnFocus, onPlatform: (String?) -> Unit, onSelect: (InstalledGame) -> Unit) {
    val firstFocus = rememberInitialFocusRequester(ready = state.games.isNotEmpty() && returnFocus.openedKey == null)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Dimens.CardWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 8.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing + 4.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing + 4.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (state.storage.gamesBytes > 0) {
                    StorageCard(state.storage, Modifier.padding(bottom = 14.dp))
                }
                Text(
                    pluralStringResource(R.plurals.library_count, state.totalCount, state.totalCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = RShopColors.TextSecondary,
                )
                if (state.platforms.size > 1) {
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "all") {
                            ConsoleChip(stringResource(R.string.library_all), selected = state.platform == null, onClick = { onPlatform(null) })
                        }
                        items(state.platforms, key = { it }) { platform ->
                            ConsoleChip(platform, selected = state.platform == platform, onClick = { onPlatform(platform) })
                        }
                    }
                }
            }
        }
        itemsIndexed(state.games, key = { _, game -> game.gameId }) { index, game ->
            InstalledCard(
                game,
                onClick = { onSelect(game) },
                focus = if (index == 0) firstFocus else null,
                modifier = Modifier.returnFocusTarget(returnFocus, game.gameId),
            )
        }
    }
}

@Composable
private fun InstalledCard(game: InstalledGame, onClick: () -> Unit, focus: FocusRequester?, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column {
        FocusableSurface(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Dimens.CoverAspectRatio)
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .then(modifier),
        ) {
            GameCover(coverGame(game.gameId, game.title, game.platform, game.coverUrl), showTitle = game.coverUrl == null, modifier = Modifier.fillMaxSize())
            if (game.updateAvailable) {
                Text(
                    stringResource(R.string.library_update_badge),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(RShopColors.Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            game.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) RShopColors.TextPrimary else RShopColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(game.platform, game.installedVersion?.let { "v$it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = RShopColors.TextTertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun GameActionsSheet(
    selection: LibrarySelection,
    onDismiss: () -> Unit,
    onOpenPage: () -> Unit,
    onUpdate: () -> Unit,
    onAskDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onForget: () -> Unit,
) {
    val game = selection.game
    val firstAction = remember { FocusRequester() }
    // The platform default width is a narrow phone dialog: too cramped on a 16:9 handheld.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .padding(horizontal = 32.dp)
                .widthIn(min = 560.dp, max = 760.dp)
                .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            GameCover(
                coverGame(game.gameId, game.title, game.platform, game.coverUrl),
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(Dimens.CoverAspectRatio)
                    .background(Color.Transparent, Dimens.CardShape),
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                game.platform?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = RShopColors.AccentBright) }
                Text(game.title, style = MaterialTheme.typography.titleLarge)
                selection.format?.let { Text(stringResource(R.string.library_format, it), style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextPrimary) }
                game.installedVersion?.let { Text(stringResource(R.string.library_version, it), style = MaterialTheme.typography.bodyMedium) }
                if (game.updateAvailable) {
                    Text(stringResource(R.string.library_catalog_version, game.catalogVersion.orEmpty()), color = RShopColors.AccentBright)
                }
                game.sizeOnDisk?.let { Text(stringResource(R.string.library_size, formatSize(it)), style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary) }
                Text(stringResource(R.string.library_installed_on, formatDate(game.installedAt)), style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary)
                if (!game.inCatalog) Text(stringResource(R.string.library_not_in_catalog), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextTertiary)
                if (selection.filesPresent == false) Text(stringResource(R.string.library_files_missing), style = MaterialTheme.typography.bodySmall, color = RShopColors.Warning)
                if (selection.confirmDelete) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.library_confirm_delete, game.title), style = MaterialTheme.typography.bodyMedium, color = RShopColors.Warning)
                }
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        selection.confirmDelete ->
                            ConsoleButton(stringResource(R.string.library_confirm), onConfirmDelete, Modifier.focusRequester(firstAction))
                        selection.filesPresent == false ->
                            ConsoleButton(stringResource(R.string.library_remove_entry), onForget, Modifier.focusRequester(firstAction))
                        game.updateAvailable ->
                            ConsoleButton(stringResource(R.string.action_update), onUpdate, Modifier.focusRequester(firstAction))
                        game.inCatalog ->
                            ConsoleButton(stringResource(R.string.action_info), onOpenPage, Modifier.focusRequester(firstAction))
                        else ->
                            ConsoleButton(stringResource(R.string.action_uninstall), onAskDelete, Modifier.focusRequester(firstAction), style = ConsoleButtonStyle.Secondary)
                    }
                    if (!selection.confirmDelete) {
                        if (game.updateAvailable && game.inCatalog) {
                            ConsoleButton(stringResource(R.string.action_info), onOpenPage, style = ConsoleButtonStyle.Secondary)
                        }
                        if (selection.filesPresent != false && (game.inCatalog || game.updateAvailable)) {
                            ConsoleButton(stringResource(R.string.action_uninstall), onAskDelete, style = ConsoleButtonStyle.Secondary)
                        }
                    }
                    ConsoleButton(stringResource(R.string.action_close), onDismiss, style = ConsoleButtonStyle.Secondary)
                }
            }
        }
    }
    LaunchedEffect(selection.confirmDelete, selection.filesPresent) {
        runCatching { firstAction.requestFocus() }
    }
}
