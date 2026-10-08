package com.rshop.ui.store

import com.rshop.ui.components.ControllerTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.rshop.R
import com.rshop.domain.model.SortOrder
import com.rshop.ui.components.ConsoleChip
import com.rshop.ui.components.GameCard
import com.rshop.ui.components.rememberInitialFocusRequester
import com.rshop.ui.components.rememberReturnFocus
import com.rshop.ui.components.returnFocusTarget
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.sourceErrorText

@Composable
fun StoreScreen(
    onOpenGame: (String) -> Unit,
    viewModel: StoreViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val games = viewModel.games.collectAsLazyPagingItems()
    val focusManager = LocalFocusManager.current
    // Start on the active filter chip rather than the search field, which would pop the keyboard.
    // Back from a game page, its card gets focus instead (see ReturnFocus).
    val returnFocus = rememberReturnFocus()
    val filterFocus = rememberInitialFocusRequester(ready = returnFocus.openedKey == null)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(Dimens.CardWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 8.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing + 4.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing + 4.dp),
    ) {
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ControllerTextField(shape = Dimens.PillShape, modifier = Modifier.weight(1f)) { fieldModifier ->
                        OutlinedTextField(
                            value = query,
                            onValueChange = viewModel::onQueryChange,
                            modifier = fieldModifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.store_search_hint)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = null)
                                    }
                                }
                            } else {
                                null
                            },
                            singleLine = true,
                            shape = Dimens.PillShape,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RShopColors.Focus,
                                unfocusedBorderColor = RShopColors.Outline,
                                focusedContainerColor = RShopColors.SurfaceHigh,
                                unfocusedContainerColor = RShopColors.Surface,
                            ),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ConsoleChip(
                        text = stringResource(R.string.store_sort, stringResource(state.sort.labelRes())),
                        selected = false,
                        onClick = viewModel::onCycleSort,
                    )
                }
                if (state.sources.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRestorer(),
                        contentPadding = PaddingValues(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "all-sources") {
                            ConsoleChip(stringResource(R.string.store_all_sources), selected = state.sourceId == null, onClick = { viewModel.onSourceSelected(null) })
                        }
                        items(state.sources, key = { "source:${it.first}" }) { (id, name) ->
                            ConsoleChip(name, selected = state.sourceId == id, onClick = { viewModel.onSourceSelected(id) })
                        }
                    }
                }
                if (state.platforms.size > 1) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRestorer(),
                        contentPadding = PaddingValues(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "all-platforms") {
                            ConsoleChip(stringResource(R.string.library_all), selected = state.platform == null, onClick = { viewModel.onPlatformSelected(null) })
                        }
                        items(state.platforms, key = { "platform:$it" }) { platform ->
                            ConsoleChip(platform, selected = state.platform == platform, onClick = { viewModel.onPlatformSelected(platform) })
                        }
                    }
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRestorer(),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "all") {
                        ConsoleChip(
                            text = stringResource(R.string.store_all),
                            selected = state.genre == null,
                            onClick = { viewModel.onGenreSelected(null) },
                            modifier = if (state.genre == null) Modifier.focusRequester(filterFocus) else Modifier,
                        )
                    }
                    items(state.genres, key = { "genre:$it" }) { genre ->
                        ConsoleChip(
                            text = genre,
                            selected = state.genre == genre,
                            onClick = { viewModel.onGenreSelected(genre) },
                            modifier = if (state.genre == genre) Modifier.focusRequester(filterFocus) else Modifier,
                        )
                    }
                }
                Text(
                    text = if (!state.isLoading && state.resultCount == 0) {
                        stringResource(R.string.store_empty)
                    } else {
                        pluralStringResource(R.plurals.store_results, state.resultCount, state.resultCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = RShopColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (state.canSearchRemote && query.isNotBlank()) {
                    RemoteSearchRow(query.trim(), state.remoteSearch, viewModel::onSearchOnSite)
                }
            }
        }
        items(count = games.itemCount, key = games.itemKey { it.id }) { index ->
            games[index]?.let { game ->
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
}

@Composable
private fun RemoteSearchRow(query: String, state: RemoteSearchState, onSearch: () -> Unit) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state == RemoteSearchState.Running) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        } else {
            ConsoleChip(text = stringResource(R.string.store_search_remote, query), selected = false, onClick = onSearch)
        }
        Spacer(Modifier.width(12.dp))
        when (state) {
            is RemoteSearchState.Done -> Text(
                pluralStringResource(R.plurals.store_search_remote_done, state.found, state.found),
                style = MaterialTheme.typography.bodyMedium,
                color = RShopColors.TextSecondary,
            )
            is RemoteSearchState.Failed -> Text(sourceErrorText(state.error), style = MaterialTheme.typography.bodyMedium, color = RShopColors.Warning)
            else -> Unit
        }
    }
}

private fun SortOrder.labelRes(): Int = when (this) {
    SortOrder.Title -> R.string.sort_title
    SortOrder.RecentlyAdded -> R.string.sort_recently_added
    SortOrder.RecentlyUpdated -> R.string.sort_recently_updated
    SortOrder.Size -> R.string.sort_size
    SortOrder.Popular -> R.string.sort_popular
}
