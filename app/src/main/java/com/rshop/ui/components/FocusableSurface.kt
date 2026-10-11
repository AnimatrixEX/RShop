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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rshop.ui.theme.ActiveTheme
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.LiquidGlass
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
    /** Draws the theme's rim around the surface: by default only when it has a fill of its own. */
    showEdge: Boolean = containerColor.alpha > 0f,
    /** The frame drawn around the surface when it has the focus; a game box shows the focus by itself. */
    focusRing: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    // Every animated value below is a State that is only read where it is used, in the layer or the draw
    // phase: when the focus moves, the two cards concerned redraw for the length of the animation but do
    // not recompose (nor rebuild their modifiers) at every frame, which a screen of cards cannot afford.
    val scale = animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "focusScale",
    )
    val ringAlpha = animateFloatAsState(if (focused && focusRing) 1f else 0f, label = "focusRing")
    val look = ActiveTheme.look
    val raised = animateFloatAsState(if (focused && glow) 1f else 0f, label = "focusElevation")
    val density = LocalDensity.current
    val restingElevationPx = with(density) { look.restingElevation.toPx() }
    val raisedElevationPx = with(density) { FocusElevation.toPx() }
    val ringWidthPx = with(density) { Dimens.FocusBorder.toPx() * 2 } // half of it is cut by the clip below
    // Only the surfaces that change color with the focus animate one.
    val fill = if (containerColor != focusedContainerColor) animateColorAsState(if (focused) focusedContainerColor else containerColor, label = "focusBackground") else null

    // Moving the focus scrolls the list just enough to show the item itself, which leaves the
    // headers above the first item and the notes under the last one out of reach. Asking for a
    // margin around the item scrolls them into view too (and keeps the focus ring uncut).
    val bringIntoView = remember { BringIntoViewRequester() }
    // Plain holder, not state: laying out hundreds of cards must not recompose each of them once more.
    val measured = remember { IntArray(2) }
    LaunchedEffect(focused) {
        if (focused && measured[0] > 0) {
            val horizontal = with(density) { FocusScrollMarginHorizontal.toPx() }
            val vertical = with(density) { FocusScrollMarginVertical.toPx() }
            bringIntoView.bringIntoView(Rect(-horizontal, -vertical, measured[0] + horizontal, measured[1] + vertical))
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { measured[0] = it.width; measured[1] = it.height }
            .bringIntoViewRequester(bringIntoView)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                val lift = raised.value
                shadowElevation = restingElevationPx + (raisedElevationPx - restingElevationPx) * lift
                this.shape = shape
                clip = false
                val shadowColor = if (lift > 0f) RShopColors.Accent else Color.Black
                ambientShadowColor = shadowColor
                spotShadowColor = shadowColor
            }
            .clip(shape)
            .then(
                when {
                    fill != null -> Modifier.drawBehind { drawRect(fill.value) }
                    containerColor.alpha > 0f -> Modifier.background(containerColor)
                    else -> Modifier
                },
            )
            .then(
                when {
                    !showEdge -> Modifier
                    look.glossy -> Modifier.background(LiquidGlass.Body, shape).border(1.2.dp, LiquidGlass.Rim, shape)
                    look.edge.alpha > 0f -> Modifier.border(1.dp, look.edge, shape)
                    else -> Modifier
                },
            )
            .then(
                if (focusRing) {
                    Modifier.drawWithContent {
                        drawContent()
                        val alpha = ringAlpha.value
                        if (alpha > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), RShopColors.Focus.copy(alpha = alpha), style = Stroke(ringWidthPx))
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
                },
            )
            // Liquid glass: a glare where the light catches the top left corner, over the content too.
            .then(
                if (look.glossy && showEdge) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                                center = Offset(size.width * 0.12f, size.height * 0.04f),
                                radius = maxOf(size.width, size.height) * 0.85f,
                            ),
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = contentAlignment,
    ) {
        content(focused)
    }
}

private val FocusElevation = 18.dp
private val FocusScrollMarginHorizontal = 24.dp
private val FocusScrollMarginVertical = 112.dp
