package com.rshop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.rshop.domain.model.Game
import com.rshop.ui.theme.RShopColors

/**
 * Cover art for a game. A generated gradient with the title is always drawn underneath, so a
 * missing, loading or broken image still yields a good-looking card.
 */
@Composable
fun GameCover(game: Game, modifier: Modifier = Modifier, showTitle: Boolean = true, thumbnail: Boolean = false) {
    val gradient = remember(game.id) { RShopColors.artworkGradient(game.id).let { (start, end) -> Brush.linearGradient(listOf(start, end)) } }
    Box(modifier.background(gradient)) {
        // The initial is for the games that have no picture: under a cover it would only be laid out for nothing.
        if (game.coverUrl == null) {
            Text(
                text = game.title.take(1).uppercase(),
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.16f),
                fontSize = 72.sp,
                fontWeight = FontWeight.Black,
            )
        }
        if (showTitle) {
            Text(
                text = game.title,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (game.coverUrl != null) {
            AsyncImage(
                // A card is always decoded at the same size, so the picture in the memory cache is
                // found again when the cards change shape (flat to 3D box): no reload, no flicker.
                model = if (thumbnail) {
                    // No fade-in on cards: dozens of them load while a list scrolls, and each fade is a redraw.
                    val context = LocalPlatformContext.current
                    remember(game.coverUrl) { ImageRequest.Builder(context).data(game.coverUrl).size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT).crossfade(false).build() }
                } else {
                    game.coverUrl
                },
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private const val THUMBNAIL_WIDTH = 320
private const val THUMBNAIL_HEIGHT = 427
