package com.rshop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.rshop.domain.model.Game
import com.rshop.ui.theme.ActiveTheme
import com.rshop.ui.theme.BackdropStyle
import com.rshop.ui.theme.RShopColors
import kotlinx.coroutines.delay

/** Full-screen ambient background that follows the highlighted game, like console dashboards. */
@Composable
fun Backdrop(highlighted: Game?, modifier: Modifier = Modifier) = Backdrop(highlight = { highlighted }, modifier = modifier)

/**
 * [highlight] is read here, not by the screen: a screen that follows the focus recomposes only this
 * background when the focus moves, not the shelves and cards around it.
 */
@Composable
fun Backdrop(highlight: () -> Game?, modifier: Modifier = Modifier) {
    // Turned off in the settings: a calm, fixed background instead of the game's colors and art.
    val wanted = highlight()?.takeIf { ActiveTheme.settings.dynamicBackdrop }

    // Running the focus along a row must not change the whole screen at every card: the background
    // follows once the focus has rested a moment (and shows the first game at once).
    var game by remember { mutableStateOf(wanted) }
    LaunchedEffect(wanted?.id) {
        if (game == null || wanted == null) {
            game = wanted
        } else {
            delay(FOLLOW_DELAY_MS)
            game = wanted
        }
    }

    val (targetStart, targetEnd) = game?.let { RShopColors.artworkGradient(it.id) }
        ?: (RShopColors.Surface to RShopColors.Background)
    val start by animateColorAsState(targetStart, tween(600), label = "backdropStart")
    val end by animateColorAsState(targetEnd, tween(600), label = "backdropEnd")

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawBehind {
                // Glass and PS2 draw their own page behind the screens: keep it.
                if (ActiveTheme.look.backdrop == BackdropStyle.Plain) drawRect(RShopColors.Background)
                if (!ActiveTheme.palette.isLight && ActiveTheme.look.artBackdrop) drawRect(
                    Brush.radialGradient(
                        colors = listOf(end.copy(alpha = 0.45f), start.copy(alpha = 0.2f), RShopColors.Background.copy(alpha = 0f)),
                        center = Offset(size.width * 0.85f, 0f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
            },
    ) {
        val coverUrl = game?.coverUrl
        val artShows = !ActiveTheme.palette.isLight && ActiveTheme.look.backdrop != BackdropStyle.Ps2 && ActiveTheme.look.artBackdrop
        if (coverUrl != null && artShows) {
            val context = LocalContext.current
            // A tiny picture stretched over the screen is already a blur: nothing is decoded at screen size
            // and no blur filter has to be computed over the whole screen at every change.
            val request = remember(coverUrl) {
                ImageRequest.Builder(context).data(coverUrl).size(BACKDROP_WIDTH, BACKDROP_HEIGHT).crossfade(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (ActiveTheme.look.glossy) 0.50f else 0.18f,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private const val FOLLOW_DELAY_MS = 220L
private const val BACKDROP_WIDTH = 48
private const val BACKDROP_HEIGHT = 64
