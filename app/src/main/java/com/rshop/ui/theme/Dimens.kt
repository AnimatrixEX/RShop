package com.rshop.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Sizes tuned for 5–7" handheld screens viewed at arm's length, and TVs from a few metres. */
object Dimens {
    val ScreenPadding = 28.dp
    val SectionSpacing = 28.dp
    val ItemSpacing = 16.dp

    val CardWidth = 132.dp
    const val CoverAspectRatio = 3f / 4f

    /** The corner of the theme in use: glass is soft, the PS2 menu almost square. */
    val CardCorner get() = ActiveTheme.look.corner
    val CardShape: Shape get() = RoundedCornerShape(CardCorner)
    val PillShape: Shape get() = if (ActiveTheme.look.squareControls) RoundedCornerShape(6.dp) else RoundedCornerShape(50)

    val FocusBorder get() = ActiveTheme.look.focusBorder
    val FocusScale: Float get() = ActiveTheme.look.focusScale

    val TopBarHeight = 64.dp
}
