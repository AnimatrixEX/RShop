package com.rshop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import timber.log.Timber

@Composable
fun ConsoleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = Dimens.PillShape,
        focusedScale = 1.06f,
        containerColor = if (selected) RShopColors.Accent else RShopColors.SurfaceHigh,
        focusedContainerColor = if (selected) RShopColors.AccentBright else RShopColors.SurfaceHighest,
        glow = false,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) RShopColors.OnAccent else RShopColors.TextPrimary,
            maxLines = 1,
        )
    }
}

/** Large gradient tile used for categories and platforms on the home screen. */
@Composable
fun CategoryTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (start, end) = RShopColors.artworkGradient(label)
    FocusableSurface(onClick = onClick, modifier = modifier, contentAlignment = Alignment.BottomStart) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.linearGradient(listOf(start, end))),
        )
        Text(
            text = label.uppercase(),
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.titleMedium,
            color = RShopColors.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
fun EmptyState(
    icon: Painter,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(Dimens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = RShopColors.TextTertiary)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = RShopColors.TextPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = RShopColors.TextSecondary, textAlign = TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(24.dp))
                action()
            }
        }
    }
}

/**
 * Focus requester that grabs focus once [ready] is true, but only when the user is navigating
 * with a controller or keyboard: touch users must not see a focus ring appear on their own.
 */
@Composable
fun rememberInitialFocusRequester(ready: Boolean = true): FocusRequester {
    val requester = remember { FocusRequester() }
    // Observable: in touch mode nothing is focused, and the first controller press switches to
    // Keyboard mode. That press lands here too, instead of on whatever the top bar offers.
    val inputMode = LocalInputModeManager.current.inputMode
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(ready, inputMode) {
        if (ready && !done && inputMode == InputMode.Keyboard) {
            withFrameNanos { } // let the target be laid out first
            try {
                requester.requestFocus()
                done = true
            } catch (e: IllegalStateException) {
                Timber.w(e, "Initial focus target not attached")
            }
        }
    }
    return requester
}
