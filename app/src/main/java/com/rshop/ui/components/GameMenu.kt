package com.rshop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rshop.R
import com.rshop.data.repository.GameListRepository
import com.rshop.domain.model.Game
import com.rshop.domain.model.GameList
import com.rshop.domain.repository.GameRepository
import com.rshop.ui.lists.AddToListDialog
import com.rshop.ui.lists.ListNameDialog
import com.rshop.ui.theme.RShopColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the game menu (Y on a card, long press) changes: favorites and custom lists. */
@HiltViewModel
class GameMenuViewModel @Inject constructor(
    private val games: GameRepository,
    private val lists: GameListRepository,
) : ViewModel() {

    val allLists: StateFlow<List<GameList>> = lists.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isFavorite(gameId: String): Flow<Boolean> = games.observeIsFavorite(gameId)

    fun listIdsOf(gameId: String): Flow<Set<Long>> = lists.observeListIdsOf(gameId)

    fun setFavorite(gameId: String, favorite: Boolean) {
        viewModelScope.launch { games.setFavorite(gameId, favorite) }
    }

    fun setMember(listId: Long, gameId: String, member: Boolean) {
        viewModelScope.launch { lists.setMember(listId, gameId, member) }
    }

    /** Creates the list and puts the game in it. */
    fun createList(name: String, gameId: String) {
        viewModelScope.launch { lists.create(name)?.let { lists.setMember(it, gameId, true) } }
    }
}

/** The game a screen opened the menu for, if any. */
class GameMenuState {
    var game by mutableStateOf<Game?>(null)
        private set

    fun open(game: Game) {
        this.game = game
    }

    fun close() {
        game = null
    }
}

@Composable
fun rememberGameMenu(): GameMenuState = remember { GameMenuState() }

/**
 * The menu of one game, opened from its card: its page, favorite, lists. Controller friendly
 * (every entry is one focus stop); B closes it.
 */
@Composable
fun GameMenuHost(state: GameMenuState, onOpen: (Game) -> Unit) {
    val game = state.game ?: return
    val viewModel: GameMenuViewModel = hiltViewModel()
    val favorite by viewModel.isFavorite(game.id).collectAsState(initial = false)
    var choosingList by remember(game.id) { mutableStateOf(false) }
    var newList by remember(game.id) { mutableStateOf(false) }

    if (!choosingList) {
        val first = remember { FocusRequester() }
        Dialog(onDismissRequest = state::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .widthIn(min = 320.dp, max = 460.dp)
                    .background(RShopColors.SurfaceHigh, RoundedCornerShape(24.dp))
                    .padding(24.dp),
            ) {
                Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                game.platform?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = RShopColors.AccentBright) }
                Spacer(Modifier.height(16.dp))
                ConsoleButton(
                    stringResource(R.string.game_menu_open),
                    onClick = {
                        state.close()
                        onOpen(game)
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(first),
                )
                Spacer(Modifier.height(10.dp))
                ConsoleButton(
                    stringResource(if (favorite) R.string.game_menu_unfavorite else R.string.game_menu_favorite),
                    onClick = {
                        viewModel.setFavorite(game.id, !favorite)
                        state.close()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = ConsoleButtonStyle.Secondary,
                )
                Spacer(Modifier.height(10.dp))
                ConsoleButton(
                    stringResource(R.string.game_menu_lists),
                    onClick = { choosingList = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = ConsoleButtonStyle.Secondary,
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    } else {
        val lists by viewModel.allLists.collectAsStateWithLifecycle()
        val memberOf by viewModel.listIdsOf(game.id).collectAsState(initial = emptySet())
        AddToListDialog(
            gameTitle = game.title,
            lists = lists,
            memberOf = memberOf,
            onToggle = { list -> viewModel.setMember(list.id, game.id, list.id !in memberOf) },
            onCreate = { newList = true },
            onDismiss = state::close,
        )
        if (newList) {
            ListNameDialog(
                title = stringResource(R.string.list_new_title),
                confirmLabel = stringResource(R.string.list_create),
                initialName = "",
                onConfirm = {
                    viewModel.createList(it, game.id)
                    newList = false
                },
                onDismiss = { newList = false },
            )
        }
    }
}
