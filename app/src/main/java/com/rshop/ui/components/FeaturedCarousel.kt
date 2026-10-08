package com.rshop.ui.components

import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.rshop.domain.model.Game
import com.rshop.ui.theme.RShopColors
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * "Featured": a few popular games turning by themselves, console-dashboard style. It stops
 * turning while a controller is on it or a finger drags it. With a controller, LEFT on the
 * leftmost button / RIGHT on the cover (nothing further that way) turn the page.
 */
@Composable
fun FeaturedCarousel(
    games: List<Game>,
    pagerState: PagerState,
    isFavorite: (Game) -> Boolean,
    onOpen: (Game) -> Unit,
    onToggleFavorite: (Game) -> Unit,
    /** "View game" button of the current page; the screen's initial focus target. */
    currentPageFocus: FocusRequester,
    modifier: Modifier = Modifier,
    autoTurnMillis: Long = 6_000,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // One "View game" requester per page; the current page's is the one handed to the screen.
    val requesters = remember(games.size) { List(games.size) { FocusRequester() } }
    var refocusPage by remember { mutableStateOf<Int?>(null) }

    // Time left before the next turn, shown by the bar under the banner. It freezes while the
    // carousel is held (controller on it, finger dragging) and resumes from where it stopped.
    val progress = remember { Animatable(0f) }
    var progressPage by remember { mutableIntStateOf(-1) }
    val dragged by pagerState.interactionSource.collectIsDraggedAsState()
    val context = LocalContext.current
    LaunchedEffect(pagerState.settledPage, focused, dragged, games.size) {
        if (progressPage != pagerState.settledPage) {
            progress.snapTo(0f)
            progressPage = pagerState.settledPage
        }
        if (focused || dragged || games.size < 2) return@LaunchedEffect
        val remaining = ((1f - progress.value) * autoTurnMillis).toLong()
        if (animationsDisabled(context)) {
            // "Remove animations" (accessibility, tests): a still bar, same timing.
            delay(remaining)
        } else {
            progress.animateTo(1f, tween(durationMillis = remaining.toInt(), easing = LinearEasing))
        }
        // Its own coroutine: the scroll it starts must not cancel it through this effect's keys.
        scope.launch { pagerState.animateScrollToPage((pagerState.settledPage + 1) % games.size) }
    }
    // A page turned with the controller: its button takes the focus over.
    LaunchedEffect(refocusPage, pagerState.settledPage) {
        val page = refocusPage ?: return@LaunchedEffect
        if (pagerState.settledPage != page) return@LaunchedEffect
        // Settled, the page is the current one: its button now carries [currentPageFocus].
        withFrameNanos { }
        runCatching { currentPageFocus.requestFocus() }.onFailure { Timber.w(it, "Featured page not ready") }
        refocusPage = null
    }

    fun turn(step: Int) {
        val target = (pagerState.currentPage + step).mod(games.size)
        refocusPage = target
        scope.launch { pagerState.animateScrollToPage(target) }
    }

    Column(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            // LEFT/RIGHT never leave the carousel (to the top bar's gear, say): they turn pages.
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
                }
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || games.size < 2) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    else -> return@onPreviewKeyEvent false
                }
                // Normal focus moves inside the banner first; at its edge, the page turns.
                if (focusManager.moveFocus(direction)) return@onPreviewKeyEvent true
                turn(if (direction == FocusDirection.Left) -1 else 1)
                true
            },
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            key = { games[it].id },
        ) { page ->
            val game = games[page]
            // Pages beside the shown one are composed for scrolling, but must not take focus:
            // at the edge of the shown page, the key handler above turns the page instead.
            val shown = page == pagerState.currentPage
            HeroBanner(
                modifier = Modifier
                    .focusProperties { onEnter = { if (!shown) cancelFocusChange() } }
                    .focusGroup(),
                game = game,
                onOpen = { onOpen(game) },
                buttonFocusRequester = if (page == pagerState.currentPage) currentPageFocus else requesters[page],
                isFavorite = isFavorite(game),
                onToggleFavorite = { onToggleFavorite(game) },
            )
        }
        if (games.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                repeat(games.size) { index ->
                    PageDot(selected = index == pagerState.currentPage, progress = { progress.value })
                }
            }
        }
    }
}

/** A page marker; the current page's grows into a bar that fills up until the next turn. */
@Composable
private fun PageDot(selected: Boolean, progress: () -> Float) {
    val width by animateDpAsState(if (selected) 40.dp else 8.dp, label = "dotWidth")
    Box(
        Modifier
            .width(width)
            .height(8.dp)
            .clip(CircleShape)
            .background(RShopColors.Outline)
            .drawBehind {
                // Read in the draw phase only: the fill animates without recomposing the row.
                if (selected) drawRect(RShopColors.AccentBright, size = size.copy(width = size.width * progress()))
            },
    )
}

private fun animationsDisabled(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
