package com.rshop.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Sizes tuned for 5–7" handheld screens viewed at arm's length, and TVs from a few metres. */
object Dimens {
    val ScreenPadding = 28.dp
    val SectionSpacing = 28.dp
    val ItemSpacing = 16.dp

    val CardWidth = 132.dp
    const val CoverAspectRatio = 3f / 4f

    val CardCorner = 14.dp
    val CardShape = RoundedCornerShape(CardCorner)
    val PillShape = RoundedCornerShape(50)

    val FocusBorder = 3.dp
    const val FocusScale = 1.07f

    val TopBarHeight = 64.dp
}
