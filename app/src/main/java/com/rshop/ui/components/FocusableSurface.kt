package com.rshop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
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
    /** Long press (touch) for a secondary action; controllers use a button instead. */
    onLongClick: (() -> Unit)? = null,
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

    // Moving the focus scrolls the list just enough to show the item itself, which leaves the
    // headers above the first item and the notes under the last one out of reach. Asking for a
    // margin around the item scrolls them into view too (and keeps the focus ring uncut).
    val bringIntoView = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    LaunchedEffect(focused, size) {
        if (focused && size != IntSize.Zero) {
            val horizontal = with(density) { FocusScrollMarginHorizontal.toPx() }
            val vertical = with(density) { FocusScrollMarginVertical.toPx() }
            bringIntoView.bringIntoView(Rect(-horizontal, -vertical, size.width + horizontal, size.height + vertical))
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .bringIntoViewRequester(bringIntoView)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation, shape, clip = false, ambientColor = RShopColors.Accent, spotColor = RShopColors.Accent)
            .clip(shape)
            .background(background)
            .border(Dimens.FocusBorder, RShopColors.Focus.copy(alpha = ringAlpha), shape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
                },
            ),
        contentAlignment = contentAlignment,
    ) {
        content(focused)
    }
}

private val FocusScrollMarginHorizontal = 24.dp
private val FocusScrollMarginVertical = 112.dp
