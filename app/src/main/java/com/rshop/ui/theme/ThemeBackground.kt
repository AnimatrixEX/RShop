package com.rshop.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the screens sit on. Plain themes are one color; Glass is lit by soft colored lights (the
 * translucent surfaces on top frost them); the PS2 style has slow pulsing columns of light.
 */
@Composable
fun ThemeBackground(modifier: Modifier = Modifier) {
    when (ActiveTheme.look.backdrop) {
        BackdropStyle.Plain -> Box(modifier.fillMaxSize().background(RShopColors.Background))
        BackdropStyle.Glass -> GlassBackground(modifier.graphicsLayer())
        BackdropStyle.Ps2 -> Ps2Background(modifier.graphicsLayer())
    }
}

@Composable
private fun GlassBackground(modifier: Modifier) {
    val accent = RShopColors.Accent
    val driftState = rememberPhase(ActiveTheme.settings.animatedBackground, GLASS_DRIFT_MS, still = 0.12f)
    val drift by driftState
    Canvas(modifier.fillMaxSize()) {
        drawRect(RShopColors.Background)
        val reach = size.maxDimension
        // Big soft color fields moving slowly: what the glass has behind it to bend and tint.
        val fields = listOf(
            Triple(accent, 0.10f, 0.15f),
            Triple(Color(0xFF6A4CFF), 0.88f, 0.85f),
            Triple(Color(0xFFFF5EA8), 0.70f, 0.20f),
            Triple(Color(0xFFFFA14D), 0.22f, 0.90f),
        )
        fields.forEachIndexed { i, (color, x, y) ->
            val angle = 2.0 * PI * (drift + i / fields.size.toFloat())
            val cx = size.width * (x + 0.06f * sin(angle).toFloat())
            val cy = size.height * (y + 0.08f * cos(angle).toFloat())
            val strength = if (i == 3) 0.20f else 0.34f
            drawRect(Brush.radialGradient(listOf(color.copy(alpha = strength), Color.Transparent), Offset(cx, cy), reach * (if (i < 2) 0.55f else 0.38f)))
        }
    }
}

private const val GLASS_DRIFT_MS = 40_000

/** A phase from 0 to 1 that cycles in [periodMs]; a fixed one (nothing animates, nothing redraws) when [animated] is off. */
@Composable
private fun rememberPhase(animated: Boolean, periodMs: Int, still: Float): State<Float> {
    val fixed = remember(still) { mutableFloatStateOf(still) }
    if (!animated) return fixed
    return rememberInfiniteTransition(label = "backgroundPhase").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = stepped(periodMs)), RepeatMode.Restart),
        label = "backgroundPhaseValue",
    )
}

/**
 * These backgrounds drift so slowly that 60 redraws a second would be pure waste on a handheld's
 * battery. The value only changes about 15 times a second, so the screen is only redrawn that often.
 */
private fun stepped(periodMs: Int): Easing {
    val steps = periodMs / 66
    return Easing { fraction -> floor(fraction * steps) / steps }
}

@Composable
private fun Ps2Background(modifier: Modifier) {
    // The Store's blue: nearly black at the top left, bright royal blue through the middle, deeper at the foot.
    val animated = ActiveTheme.settings.animatedBackground
    val phaseState = rememberPhase(animated, PULSE_MS, still = 0.3f)
    val phase by phaseState
    Canvas(
        modifier
            .fillMaxSize()
            .drawWithCache {
                // Still page: the curves are built once per size. A stroked curve this wide is rasterized on the CPU,
                // and a new Path at every redraw made the render thread redo that work for every frame.
                val still = if (animated) null else List(WAVES) { wavePath(size, it, 0.3f) }
                onDrawWithContent {
                    drawContent()
                    for (i in 0 until WAVES) {
                        val path = still?.get(i) ?: wavePath(size, i, (phase + i / WAVES.toFloat()) % 1f)
                        drawPath(path, Color.White.copy(alpha = 0.07f), style = Stroke(width = 1.6f * density))
                    }
                }
            },
    ) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF040B38), Color(0xFF0B2FC0), Color(0xFF0A3FD8), Color(0xFF05185E)), Offset.Zero, Offset(size.width, size.height)))
        // A soft bright centre.
        drawRect(Brush.radialGradient(listOf(Color(0xFF3F7BFF).copy(alpha = 0.30f), Color.Transparent), Offset(size.width * 0.55f, size.height * 0.5f), size.maxDimension * 0.55f))
    }
}

/** One long curve of light, like the waves behind the Store's menus; [shift] (0 to 1) moves it. */
private fun wavePath(size: Size, index: Int, shift: Float): Path {
    val baseY = size.height * (0.15f + 0.7f * index / WAVES)
    val swing = sin(2.0 * PI * shift).toFloat()
    return Path().apply {
        moveTo(-size.width * 0.1f, baseY)
        cubicTo(
            size.width * 0.25f, baseY - size.height * (0.25f + 0.1f * swing),
            size.width * 0.65f, baseY + size.height * (0.30f - 0.1f * swing),
            size.width * 1.1f, baseY - size.height * 0.1f,
        )
    }
}

private const val WAVES = 4
private const val PULSE_MS = 24_000
