package com.rshop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.rshop.domain.model.Game
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(horizontal = Dimens.ScreenPadding),
        style = MaterialTheme.typography.titleLarge,
        color = RShopColors.TextPrimary,
    )
}

/** Horizontal row of game cards. Coming back to the row with the D-pad restores the last focused card. */
@Composable
fun GameShelf(
    title: String,
    games: List<Game>,
    onGameClick: (Game) -> Unit,
    modifier: Modifier = Modifier,
    onGameFocused: (Game) -> Unit = {},
    returnFocus: ReturnFocus? = null,
    shelfKey: String = title,
) {
    if (games.isEmpty()) return
    val rowState = rememberLazyListState()
    // Coming back from a game opened here: bring its card into view so it can take focus back.
    val returnId = returnFocus?.openedKey?.takeIf { returnFocus.isRestoring && it.startsWith("$shelfKey:") }?.removePrefix("$shelfKey:")
    LaunchedEffect(returnId) {
        val index = games.indexOfFirst { it.id == returnId }
        if (index >= 0 && rowState.layoutInfo.visibleItemsInfo.none { it.index == index }) rowState.scrollToItem(index)
    }
    Column(modifier) {
        SectionHeader(title)
        LazyRow(
            state = rowState,
            modifier = Modifier.focusRestorer(),
            // Vertical padding leaves room for the focus scale and glow, which would otherwise be clipped.
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            items(games, key = { it.id }) { game ->
                val key = "$shelfKey:${game.id}"
                GameCard(
                    game = game,
                    onClick = {
                        returnFocus?.onOpen(key)
                        onGameClick(game)
                    },
                    onFocused = onGameFocused,
                    modifier = Modifier
                        .width(Dimens.CardWidth)
                        .then(if (returnFocus != null) Modifier.returnFocusTarget(returnFocus, key) else Modifier),
                )
            }
        }
    }
}
