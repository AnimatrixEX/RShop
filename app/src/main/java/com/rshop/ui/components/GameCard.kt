package com.rshop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import com.rshop.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.RectangleShape
import com.rshop.domain.model.CoverStyle
import com.rshop.domain.model.Game
import com.rshop.ui.theme.ActiveTheme
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.gameGenreText

@Composable
fun GameCard(
    game: Game,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (Game) -> Unit = {},
    /** Opens the game's menu: Y on a controller, long press on a touch screen. */
    onMenu: ((Game) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val titleColor by animateColorAsState(if (focused) RShopColors.TextPrimary else RShopColors.TextSecondary, label = "cardTitle")

    val prefetch = LocalDetailsPrefetch.current
    LaunchedEffect(focused) {
        if (focused) {
            delay(PREFETCH_DELAY_MS)
            prefetch(game)
        }
    }

    val currentOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) {
        if (focused) currentOnFocused(game)
    }

    Column(modifier) {
        val box3d = ActiveTheme.settings.coverStyle == CoverStyle.Box3d
        val installed = game.id in LocalInstalledIds.current
        // What is on the front of the card: the cover, the installed mark, the console.
        val front: @Composable BoxScope.() -> Unit = {
            GameCover(game = game, showTitle = game.coverUrl == null, modifier = Modifier.fillMaxSize())
            if (installed) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.card_installed),
                    tint = RShopColors.Success,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                )
            }
            game.platform?.let { platform ->
                Text(
                    text = platform,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
        FocusableSurface(
            onClick = onClick,
            onLongClick = onMenu?.let { menu -> { menu(game) } },
            showEdge = !box3d,
            focusRing = !box3d,
            glow = !box3d,
            shape = if (box3d) RectangleShape else Dimens.CardShape,
            focusedScale = if (box3d) 1.04f else Dimens.FocusScale,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Dimens.CoverAspectRatio)
                .onPreviewKeyEvent { event ->
                    if (onMenu != null && event.type == KeyEventType.KeyDown && event.key == Key.ButtonY) {
                        onMenu(game)
                        true
                    } else {
                        false
                    }
                },
        ) { isFocused ->
            if (box3d) {
                GameCase(seed = game.id, title = game.title, focused = isFocused, modifier = Modifier.fillMaxSize(), front = front)
            } else {
                front()
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = game.title,
            style = MaterialTheme.typography.titleSmall,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        gameGenreText(game, max = 2)?.let { genre ->
            Text(
                text = genre,
                style = MaterialTheme.typography.bodySmall,
                color = RShopColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
