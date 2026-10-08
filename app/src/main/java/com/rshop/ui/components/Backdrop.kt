package com.rshop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rshop.domain.model.Game
import com.rshop.ui.theme.ActiveTheme
import com.rshop.ui.theme.RShopColors

/** Full-screen ambient background that follows the highlighted game, like console dashboards. */
@Composable
fun Backdrop(highlighted: Game?, modifier: Modifier = Modifier) {
    // Turned off in the settings: a calm, fixed background instead of the game's colors and art.
    val game = highlighted?.takeIf { ActiveTheme.settings.dynamicBackdrop }
    val (targetStart, targetEnd) = game?.let { RShopColors.artworkGradient(it.id) }
        ?: (RShopColors.Surface to RShopColors.Background)
    val start by animateColorAsState(targetStart, tween(600), label = "backdropStart")
    val end by animateColorAsState(targetEnd, tween(600), label = "backdropEnd")

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(RShopColors.Background)
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(end.copy(alpha = 0.45f), start.copy(alpha = 0.2f), RShopColors.Background.copy(alpha = 0f)),
                        center = Offset(size.width * 0.85f, 0f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
            },
    ) {
        val coverUrl = game?.coverUrl
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.18f,
                modifier = Modifier
                    .matchParentSize()
                    .blur(48.dp),
            )
        }
    }
}
