package com.rshop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

/**
 * Base building block for everything selectable: grows, glows and gets a white ring when focused
 * with a D-pad or controller, and still behaves as a normal clickable surface for touch.
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Dimens.CardShape,
    focusedScale: Float = Dimens.FocusScale,
    containerColor: Color = Color.Transparent,
    focusedContainerColor: Color = containerColor,
    glow: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "focusScale",
    )
    val ringAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "focusRing")
    val elevation by animateDpAsState(if (focused && glow) 18.dp else 0.dp, label = "focusElevation")
    val background by animateColorAsState(if (focused) focusedContainerColor else containerColor, label = "focusBackground")

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation, shape, clip = false, ambientColor = RShopColors.Accent, spotColor = RShopColors.Accent)
            .clip(shape)
            .background(background)
            .border(Dimens.FocusBorder, RShopColors.Focus.copy(alpha = ringAlpha), shape)
            .clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick),
        contentAlignment = contentAlignment,
    ) {
        content(focused)
    }
}
