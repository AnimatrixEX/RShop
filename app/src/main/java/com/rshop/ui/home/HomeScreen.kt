package com.rshop.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rshop.R
import com.rshop.domain.model.Game
import com.rshop.ui.components.Backdrop
import com.rshop.ui.components.CategoryTile
import com.rshop.ui.components.EmptyState
import com.rshop.ui.components.GameShelf
import com.rshop.ui.components.FeaturedCarousel
import com.rshop.ui.components.SectionHeader
import com.rshop.ui.components.rememberInitialFocusRequester
import com.rshop.ui.components.rememberReturnFocus
import com.rshop.ui.theme.Dimens
import com.rshop.ui.util.genreLabel

const val HOME_LIST_TAG = "home_list"

@Composable
fun HomeScreen(
    onOpenGame: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlatform: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(state, onOpenGame, onOpenGenre, onOpenPlatform, viewModel::onToggleFavorite)
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenGame: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlatform: (String) -> Unit,
    onToggleFavorite: (Game, Boolean) -> Unit,
) {
    // The backdrop follows whichever card is highlighted, else the featured game on screen.
    var highlighted by remember { mutableStateOf<Game?>(null) }
    val pagerState = rememberPagerState { state.featured.size }
    val returnFocus = rememberReturnFocus()
    // The hero takes focus on first display, or when it is what opened the game page.
    val heroFocus = rememberInitialFocusRequester(
        ready = state.featured.isNotEmpty() && returnFocus.openedKey.let { it == null || it == HERO_KEY },
    )
    val openGame: (Game) -> Unit = { onOpenGame(it.id) }
    val highlight: (Game) -> Unit = { highlighted = it }
    val listState = rememberLazyListState()

    // Back from a game: a shelf may have appeared above ("Recently viewed"), so scroll the opened
    // card's shelf into view first, or the card would not exist to take focus back.
    LaunchedEffect(state.isLoading, returnFocus.isRestoring) {
        if (state.isLoading || !returnFocus.isRestoring) return@LaunchedEffect
        val shelf = returnFocus.openedKey?.substringBefore(':') ?: return@LaunchedEffect
        val position = SHELF_KEYS.indexOf(shelf).takeIf { it >= 0 } ?: return@LaunchedEffect
        val index = position + if (state.featured.isNotEmpty()) 1 else 0
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(highlighted = highlighted ?: state.featured.getOrNull(pagerState.currentPage))

        // Wait for the first real state: if the list were composed without the hero, the
        // LazyColumn would keep "Recently added" pinned on top and insert the hero off-screen.
        if (state.isLoading) return@Box

        if (state.isCatalogEmpty) {
            EmptyState(
                icon = painterResource(R.drawable.ic_library),
                title = stringResource(R.string.home_empty_title),
                body = stringResource(R.string.home_empty_body),
            )
            return@Box
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.testTag(HOME_LIST_TAG),
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing - 14.dp),
        ) {
            if (state.featured.isNotEmpty()) {
                item(key = "hero") {
                    FeaturedCarousel(
                        games = state.featured,
                        pagerState = pagerState,
                        isFavorite = { it.id in state.favoriteIds },
                        onOpen = { game ->
                            returnFocus.onOpen(HERO_KEY)
                            openGame(game)
                        },
                        onToggleFavorite = { game -> onToggleFavorite(game, game.id in state.favoriteIds) },
                        currentPageFocus = heroFocus,
                        modifier = Modifier
                            .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp)
                            // Back on the carousel: the backdrop follows the featured game again.
                            .onFocusChanged { if (it.hasFocus) highlighted = null },
                    )
                }
            }
            item(key = "viewed") {
                GameShelf(stringResource(R.string.home_recently_viewed), state.recentlyViewed, openGame, onGameFocused = highlight, returnFocus = returnFocus, shelfKey = "viewed")
            }
            item(key = "favorites") {
                GameShelf(stringResource(R.string.home_favorites), state.favorites, openGame, onGameFocused = highlight, returnFocus = returnFocus, shelfKey = "favorites")
            }
            item(key = "recent") {
                GameShelf(stringResource(R.string.home_recently_added), state.recentlyAdded, openGame, onGameFocused = highlight, returnFocus = returnFocus, shelfKey = "recent")
            }
            item(key = "popular") {
                GameShelf(stringResource(R.string.home_popular), state.popular, openGame, onGameFocused = highlight, returnFocus = returnFocus, shelfKey = "popular")
            }
            item(key = "updated") {
                GameShelf(stringResource(R.string.home_recently_updated), state.recentlyUpdated, openGame, onGameFocused = highlight, returnFocus = returnFocus, shelfKey = "updated")
            }
            if (state.genres.isNotEmpty()) {
                item(key = "genres") {
                    TileRow(stringResource(R.string.home_categories), state.genres, onOpenGenre) { genreLabel(it) }
                }
            }
            if (state.platforms.isNotEmpty()) {
                item(key = "platforms") {
                    TileRow(stringResource(R.string.home_platforms), state.platforms, onOpenPlatform)
                }
            }
        }
    }
}

private const val HERO_KEY = "hero"

/** Shelf item keys, in list order (right after the hero). */
private val SHELF_KEYS = listOf("viewed", "favorites", "recent", "popular", "updated")

@Composable
private fun TileRow(title: String, labels: List<String>, onClick: (String) -> Unit, labelOf: @Composable (String) -> String = { it }) {
    Column {
        SectionHeader(title)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            items(labels, key = { it }) { label ->
                CategoryTile(label = labelOf(label), onClick = { onClick(label) }, modifier = Modifier.size(width = 180.dp, height = 80.dp))
            }
        }
    }
}
