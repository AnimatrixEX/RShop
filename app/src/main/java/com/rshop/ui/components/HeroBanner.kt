package com.rshop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rshop.R
import com.rshop.domain.model.Game
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.rememberGameMetaLine

private val HeroShape = RoundedCornerShape(24.dp)
private val MinWidthForCover = 520.dp

@Composable
fun HeroBanner(
    game: Game,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    buttonFocusRequester: FocusRequester = remember { FocusRequester() },
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
) {
    val (start, end) = RShopColors.artworkGradient(game.id)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(HeroShape)
            .background(Brush.linearGradient(listOf(start, end))),
    ) {
        val showCover = maxWidth >= MinWidthForCover
        if (game.coverUrl != null) {
            AsyncImage(
                model = game.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.45f,
                modifier = Modifier.matchParentSize(),
            )
        }
        // Darkens the text side so the title stays readable on any artwork.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_featured).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = RShopColors.AccentBright,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.displaySmall,
                    color = RShopColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = rememberGameMetaLine(game),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RShopColors.TextSecondary,
                    maxLines = 1,
                )
                game.description?.let { description ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RShopColors.TextPrimary.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConsoleButton(
                        text = stringResource(R.string.action_view_game),
                        onClick = onOpen,
                        modifier = Modifier.focusRequester(buttonFocusRequester),
                    )
                    Spacer(Modifier.width(12.dp))
                    FavoriteButton(isFavorite = isFavorite, onToggle = onToggleFavorite)
                }
            }
            // On narrow (portrait phone) screens the text gets the whole banner.
            if (!showCover) return@Row
            Spacer(Modifier.width(24.dp))
            // Focusable too: gives D-pad RIGHT a target inside the banner instead of jumping to the top bar.
            FocusableSurface(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(Dimens.CoverAspectRatio),
            ) {
                GameCover(game = game, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
