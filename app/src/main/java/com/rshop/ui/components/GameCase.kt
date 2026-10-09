package com.rshop.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rshop.ui.theme.RShopColors
import kotlin.math.roundToInt

/**
 * A game box seen in perspective: the front turned a little to show the spine, a plastic rim and a
 * gloss across the front, a soft shadow on the shelf. Resting boxes are turned away; the one with
 * the focus comes round to face the player and sways gently, like a case held in the hand.
 *
 * [front] draws the cover (and anything that belongs on it); [seed] picks the spine colors so a
 * game always has the same spine.
 *
 * Cheap on purpose, since a grid shows dozens: the geometry is worked out while laying out (no
 * nested layout pass), and every animated value is read where it is drawn, so a turning box never
 * recomposes anything.
 */
@Composable
fun GameCase(
    seed: String,
    title: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    front: @Composable BoxScope.() -> Unit,
) {
    val turned = animateFloatAsState(
        targetValue = if (focused) FOCUSED_TURN else RESTING_TURN,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "caseTurn",
    )
    val sway = rememberSway(focused)
    val lift = animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "caseLift")
    val (spineStart, spineEnd) = remember(seed) { RShopColors.artworkGradient(seed) }
    val camera = 14f * LocalDensity.current.density

    Box(modifier) {
        // The shelf shadow: wider and darker under the box that is raised.
        Canvas(Modifier.fillMaxSize()) {
            val raised = lift.value
            val width = size.width * (0.74f + 0.06f * raised)
            drawOval(
                brush = Brush.radialGradient(
                    listOf(Color.Black.copy(alpha = 0.42f - 0.08f * raised), Color.Transparent),
                    center = Offset(size.width * 0.52f, size.height * 0.955f),
                    radius = width / 2f,
                ),
                topLeft = Offset(size.width * 0.52f - width / 2f, size.height * 0.915f),
                size = Size(width, size.height * 0.08f),
            )
        }

        // Spine: hinged on the left edge of the front, a quarter turn behind it.
        Box(
            Modifier
                .casePart(spine = true, angle = { turned.value + sway.value }, lift = { lift.value }, cameraPx = camera)
                .background(Brush.horizontalGradient(listOf(spineStart.darken(0.45f), spineEnd.darken(0.62f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                modifier = Modifier.verticalText(),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Front: the cover under a gloss, inside a thin plastic rim.
        Box(
            Modifier
                .casePart(spine = false, angle = { turned.value + sway.value }, lift = { lift.value }, cameraPx = camera)
                .clip(RoundedCornerShape(2.dp))
                .drawWithContent {
                    drawContent()
                    // Light sliding across the plastic: a bright diagonal band, brighter when the box faces the player.
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0.00f),
                            0.30f to Color.White.copy(alpha = 0.16f + 0.08f * lift.value),
                            0.42f to Color.White.copy(alpha = 0.02f),
                            1f to Color.Black.copy(alpha = 0.18f),
                        ),
                    )
                }
                .border(1.dp, Color.White.copy(alpha = 0.38f), RoundedCornerShape(2.dp)),
            content = front,
        )
    }
}

/**
 * Sizes and places one face of the box inside the card, and turns it on its own layer. The front
 * is 80% of the card wide and 90% high, the spine a sixth of the front; both hinge on the same
 * vertical edge, so turning the angle turns the whole box.
 */
private fun Modifier.casePart(spine: Boolean, angle: () -> Float, lift: () -> Float, cameraPx: Float): Modifier =
    layout { measurable, constraints ->
        val parentWidth = constraints.maxWidth
        val parentHeight = constraints.maxHeight
        val faceWidth = (parentWidth * FACE_WIDTH).roundToInt()
        val faceHeight = (parentHeight * FACE_HEIGHT).roundToInt()
        val spineWidth = (faceWidth * SPINE_RATIO).roundToInt()
        // The box is centered with its spine counted in.
        val faceLeft = (parentWidth - faceWidth) / 2 + spineWidth / 2
        val top = (parentHeight - faceHeight) / 2
        val width = if (spine) spineWidth else faceWidth
        val left = if (spine) faceLeft - spineWidth else faceLeft
        val placeable = measurable.measure(Constraints.fixed(width.coerceAtLeast(1), faceHeight.coerceAtLeast(1)))
        val raise = LIFT.dp.toPx()
        layout(parentWidth, parentHeight) {
            placeable.placeWithLayer(left, top) {
                rotationY = if (spine) angle() - 90f else angle()
                transformOrigin = TransformOrigin(if (spine) 1f else 0f, 0.5f)
                cameraDistance = cameraPx
                translationY = -raise * lift()
            }
        }
    }

/**
 * A slow back-and-forth turn when the box takes the focus: a few swings, then it rests. Nothing
 * is animated for the other boxes, nor for this one once it has settled, so a handheld left on a
 * card does not keep redrawing the screen.
 */
@Composable
private fun rememberSway(active: Boolean): State<Float> {
    val sway = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            sway.snapTo(-SWAY)
            repeat(SWINGS) { i ->
                sway.animateTo(if (i % 2 == 0) SWAY else -SWAY, tween(1400, easing = FastOutSlowInEasing))
            }
            sway.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        } else {
            sway.snapTo(0f)
        }
    }
    return sway.asState()
}

/** Text running bottom to top, for a spine; it is laid out along the height it is given. */
private fun Modifier.verticalText(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight.coerceAtLeast(1)))
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2) {
            rotationZ = -90f
        }
    }
}

private fun Color.darken(by: Float): Color = Color(red * (1f - by), green * (1f - by), blue * (1f - by), alpha)

private const val RESTING_TURN = 33f
private const val FOCUSED_TURN = 9f
private const val SWAY = 6f
private const val SWINGS = 4
private const val FACE_WIDTH = 0.80f
private const val FACE_HEIGHT = 0.90f
private const val SPINE_RATIO = 0.16f
private const val LIFT = 6
