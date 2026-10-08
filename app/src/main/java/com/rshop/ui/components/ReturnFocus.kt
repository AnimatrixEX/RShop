package com.rshop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import timber.log.Timber

/**
 * Gives focus back to the card that opened another screen. Coming back recreates the screen,
 * which would otherwise focus its default target (hero, filters, first card) and lose the
 * controller user's place. Saved with the screen's state, so it survives navigation.
 */
@Stable
class ReturnFocus internal constructor(
    private val opened: MutableState<String?>,
    private val pending: MutableState<Boolean>,
) {
    // Set on the screen instance that opened the card: it is left, not returned to. Not saved,
    // so the instance composed when coming back starts without it and restores.
    private var leaving by mutableStateOf(false)

    /** Key of the last card that opened another screen; null until one did. */
    val openedKey: String? get() = opened.value

    /** True until the opened card got its focus back. */
    val isRestoring: Boolean get() = pending.value && !leaving

    fun onOpen(key: String) {
        leaving = true
        opened.value = key
        pending.value = true
    }

    internal fun done() {
        pending.value = false
    }

    /** Whether [key] is the card waiting to get focus back. */
    fun isTarget(key: String): Boolean = isRestoring && opened.value == key
}

@Composable
fun rememberReturnFocus(): ReturnFocus {
    val opened = rememberSaveable { mutableStateOf<String?>(null) }
    val pending = rememberSaveable { mutableStateOf(false) }
    return remember { ReturnFocus(opened, pending) }
}

/** Makes this card take focus back when it is the one [returnFocus] is waiting for. */
@Composable
fun Modifier.returnFocusTarget(returnFocus: ReturnFocus, key: String): Modifier {
    if (!returnFocus.isTarget(key)) return this
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // In touch mode, wait for the first controller press (it switches the mode) to restore.
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(inputMode) {
        if (inputMode != InputMode.Keyboard) return@LaunchedEffect
        // The page being left still holds focus during the back transition and drops it when it
        // goes away: keep asking until the card really has focus.
        val until = System.nanoTime() + RESTORE_WINDOW_NS
        while (!focused && System.nanoTime() < until) {
            withFrameNanos { }
            try {
                requester.requestFocus()
            } catch (e: IllegalStateException) {
                Timber.w(e, "Return focus target not attached")
            }
        }
        returnFocus.done()
    }
    return focusRequester(requester).onFocusChanged { focused = it.isFocused }
}

private const val RESTORE_WINDOW_NS = 1_500_000_000L
