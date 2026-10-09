package com.rshop.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

/**
 * Hosts a text field so a controller can move over it without the (often fullscreen) keyboard
 * popping up: with a controller the field is one focus stop, and pressing A starts typing.
 * Touch is unchanged: tapping the field edits it.
 *
 * [field] gets the modifier to apply to the text field itself.
 */
@Composable
fun ControllerTextField(
    shape: Shape,
    modifier: Modifier = Modifier,
    /** Lets the screen move the focus onto the box (the search shortcut). */
    boxFocus: FocusRequester? = null,
    field: @Composable (fieldModifier: Modifier) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val inputMode = LocalInputModeManager.current.inputMode
    val keyboard = LocalSoftwareKeyboardController.current

    Box(
        modifier
            .border(Dimens.FocusBorder, if (focused) RShopColors.Focus else Color.Transparent, shape)
            .onPreviewKeyEvent { event ->
                val press = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.ButtonA)
                if (focused && press) {
                    editing = true
                    fieldFocus.requestFocus()
                    keyboard?.show()
                    true
                } else {
                    false
                }
            }
            .then(if (boxFocus != null) Modifier.focusRequester(boxFocus) else Modifier)
            .focusable(interactionSource = interaction),
    ) {
        field(
            Modifier
                .focusRequester(fieldFocus)
                // Reachable by touch at any time; by controller only once A was pressed on the box.
                .focusProperties { canFocus = editing || inputMode == InputMode.Touch }
                .onFocusChanged { if (!it.isFocused) editing = false },
        )
    }
}
