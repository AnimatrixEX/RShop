package com.rshop.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rshop.R
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.ConsoleChip
import com.rshop.ui.components.EmptyState
import com.rshop.ui.components.GameCard
import com.rshop.ui.components.rememberInitialFocusRequester
import com.rshop.ui.components.rememberReturnFocus
import com.rshop.ui.components.returnFocusTarget
import com.rshop.ui.lists.ListNameDialog
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

/** Favorites plus the user's own lists: chips choose the list, the grid shows its games. */
@Composable
fun FavoritesScreen(
    onOpenGame: (String) -> Unit,
    onBrowseStore: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    // Back from a game page, the card that opened it gets focus again.
    val returnFocus = rememberReturnFocus()
    val chipsFocus = rememberInitialFocusRequester(ready = !state.loading && returnFocus.openedKey == null)

    if (!state.loading) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(Dimens.CardWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 8.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing + 4.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing + 4.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().focusRestorer(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "favorites") {
                            ConsoleChip(
                                text = stringResource(R.string.favorites_builtin, state.favoritesCount),
                                selected = state.selected == null,
                                onClick = { viewModel.onSelect(null) },
                                modifier = if (state.selected == null) Modifier.focusRequester(chipsFocus) else Modifier,
                            )
                        }
                        items(state.lists, key = { "list:${it.id}" }) { list ->
                            ConsoleChip(
                                text = "${list.name} (${list.games})",
                                selected = state.selected?.id == list.id,
                                onClick = { viewModel.onSelect(list.id) },
                                modifier = if (state.selected?.id == list.id) Modifier.focusRequester(chipsFocus) else Modifier,
                            )
                        }
                        item(key = "new") {
                            ConsoleChip(stringResource(R.string.list_new), selected = false, onClick = viewModel::onAskCreate)
                        }
                    }
                    state.selected?.let {
                        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConsoleButton(stringResource(R.string.list_rename), viewModel::onAskRename, style = ConsoleButtonStyle.Secondary)
                            ConsoleButton(stringResource(R.string.list_delete), viewModel::onAskDelete, style = ConsoleButtonStyle.Secondary)
                        }
                    }
                    Text(
                        pluralStringResource(R.plurals.favorites_games, state.games.size, state.games.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = RShopColors.TextSecondary,
                    )
                }
            }
            if (state.games.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = painterResource(R.drawable.ic_library),
                        title = stringResource(if (state.selected == null) R.string.favorites_empty_title else R.string.list_empty_title),
                        body = stringResource(if (state.selected == null) R.string.favorites_empty_body else R.string.list_empty_body),
                        modifier = Modifier.padding(vertical = 24.dp),
                        action = {
                            ConsoleButton(stringResource(R.string.action_browse_store), onBrowseStore)
                        },
                    )
                }
            }
            items(state.games, key = { it.id }) { game ->
                GameCard(
                    game = game,
                    onClick = {
                        returnFocus.onOpen(game.id)
                        onOpenGame(game.id)
                    },
                    modifier = Modifier.returnFocusTarget(returnFocus, game.id),
                )
            }
        }
    }

    when (val current = dialog) {
        FavoritesDialog.Create -> ListNameDialog(
            title = stringResource(R.string.list_new_title),
            confirmLabel = stringResource(R.string.list_create),
            initialName = "",
            onConfirm = viewModel::onCreate,
            onDismiss = viewModel::onDismissDialog,
        )
        is FavoritesDialog.Rename -> ListNameDialog(
            title = stringResource(R.string.list_rename),
            confirmLabel = stringResource(R.string.list_rename_confirm),
            initialName = current.list.name,
            onConfirm = { viewModel.onRename(current.list, it) },
            onDismiss = viewModel::onDismissDialog,
        )
        is FavoritesDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = viewModel::onDismissDialog,
            text = { Text(stringResource(R.string.list_delete_confirm, current.list.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.onDelete(current.list) }) { Text(stringResource(R.string.list_delete)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = RShopColors.SurfaceHigh,
        )
        null -> Unit
    }
}

