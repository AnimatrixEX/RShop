package com.rshop.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rshop.R
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import com.rshop.ui.theme.ActiveTheme
import com.rshop.ui.theme.liquidGlass
import com.rshop.ui.theme.RShopColors

/** One button of the controller and what it does on the current screen. */
data class ControllerHint(val button: String, @StringRes val label: Int)

object ControllerHints {
    val Select = ControllerHint("A", R.string.hint_select)
    val Back = ControllerHint("B", R.string.hint_back)
    val GameMenu = ControllerHint("Y", R.string.hint_game_menu)
    val Search = ControllerHint("X", R.string.hint_search)
    val Tabs = ControllerHint("L1 R1", R.string.hint_tabs)

    /** Tabs that show game cards: Y opens the menu of the focused game. */
    val OnCards = listOf(Select, Back, GameMenu, Search, Tabs)
    val OnTabs = listOf(Select, Back, Search, Tabs)
    val OnPage = listOf(Select, Back)
}

/**
 * Reminder of the controller buttons, shown only while the controller (or a keyboard) is what the
 * player uses: a touch screen user never sees it.
 */
@Composable
fun ControllerHintsBar(hints: List<ControllerHint>, modifier: Modifier = Modifier, alwaysVisible: Boolean = false) {
    val usingController = alwaysVisible || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val onBar = ActiveTheme.look.accentBars
    AnimatedVisibility(
        visible = usingController && hints.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val glass = ActiveTheme.look.glossy
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier
                .then(if (glass) Modifier else Modifier.fillMaxWidth())
                .then(
                    if (glass) {
                        Modifier
                    } else if (onBar) {
                        Modifier.background(RShopColors.Accent)
                    } else if (ActiveTheme.look.hairlines) {
                        // The Switch's footer: a thin rule over a bar the color of the page.
                        val rule = RShopColors.Outline
                        Modifier.drawBehind { drawLine(rule, Offset(24.dp.toPx(), 0f), Offset(size.width - 24.dp.toPx(), 0f), 1.dp.toPx()) }
                    } else {
                        Modifier.background(RShopColors.Surface)
                    },
                )
                .then(
                    // Liquid glass: the hints float in a capsule above the content.
                    if (ActiveTheme.look.glossy) Modifier.padding(horizontal = 24.dp, vertical = 6.dp) else Modifier,
                )
                .then(if (ActiveTheme.look.glossy) Modifier.liquidGlass(CircleShape) else Modifier)
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hints.forEachIndexed { index, hint ->
                if (index > 0) Spacer(Modifier.padding(start = 18.dp))
                Box(
                    Modifier
                        .height(20.dp)
                        .widthIn(min = 20.dp)
                        .background(if (onBar) Color.White.copy(alpha = 0.28f) else RShopColors.SurfaceHighest, RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        hint.button,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    stringResource(hint.label),
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (onBar) RShopColors.OnAccent else RShopColors.TextSecondary,
                )
            }
        }
        }
    }
}

/**
 * "Search" was asked with the controller (X): the Store comes up and takes it from here, once its
 * search field is on screen.
 */
class SearchSignal {
    var pending by mutableStateOf(false)
}

val LocalSearchSignal = staticCompositionLocalOf { SearchSignal() }
