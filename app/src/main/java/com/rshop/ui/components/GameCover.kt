package com.rshop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.rshop.domain.model.Game
import com.rshop.ui.theme.RShopColors

/**
 * Cover art for a game. A generated gradient with the title is always drawn underneath, so a
 * missing, loading or broken image still yields a good-looking card.
 */
@Composable
fun GameCover(game: Game, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val (start, end) = RShopColors.artworkGradient(game.id)
    Box(modifier.background(Brush.linearGradient(listOf(start, end)))) {
        Text(
            text = game.title.take(1).uppercase(),
            modifier = Modifier.align(Alignment.Center),
            color = Color.White.copy(alpha = 0.16f),
            fontSize = 72.sp,
            fontWeight = FontWeight.Black,
        )
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
                model = game.coverUrl,
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
