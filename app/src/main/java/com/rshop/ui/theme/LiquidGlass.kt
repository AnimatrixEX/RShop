package com.rshop.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The look of Apple's Liquid Glass, as far as a plain drawing can go: a clear body that is
 * lighter near the top, and a rim lit from two corners (bright at the top left, a fainter echo at
 * the bottom right), like light running along the edge of a glass pane. Content behind shows
 * through the body; the blur comes from the soft lights painted behind the whole screen.
 */
object LiquidGlass {
    /** Light on the rim: strong where the light hits, almost none along the shadowed sides. */
    val Rim: Brush = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.85f),
        0.35f to Color.White.copy(alpha = 0.10f),
        0.65f to Color.White.copy(alpha = 0.06f),
        1f to Color.White.copy(alpha = 0.50f),
    )

    /** The pane itself: a veil that thins out from the top. */
    val Body: Brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.06f)))

    /** A broad highlight laid over the top left corner of big panes, where the light catches. */
    fun glare(): Brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent))
}

/** A floating pane of liquid glass (a capsule for bars, a rounded rectangle for panels). */
fun Modifier.liquidGlass(shape: Shape): Modifier =
    this.background(LiquidGlass.Body, shape).border(1.2.dp, LiquidGlass.Rim, shape)
