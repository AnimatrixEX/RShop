package com.rshop.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rshop.R
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.rememberInitialFocusRequester
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.downloadErrorMessage

/**
 * The site's own page, shown in a real browser (Gecko). The user reads it, deals with whatever
 * it asks (consent, CAPTCHA, countdown) and clicks the download link; the file is read straight
 * into the app's folder and installed, then the browser closes. Nothing is clicked for the user.
 */
@Composable
fun BrowserScreen(onClose: () -> Unit, viewModel: BrowserViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val event by viewModel.events.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var browser by remember { mutableStateOf<GeckoBrowser?>(null) }
    var state by remember { mutableStateOf(BrowserState()) }
    val closeFocus = rememberInitialFocusRequester()

    BackHandler {
        if (browser?.goBack() != true) onClose()
    }

    LaunchedEffect(event) {
        when (val current = event) {
            null -> return@LaunchedEffect
            BrowserEvent.Started -> {
                onClose()
                return@LaunchedEffect
            }
            BrowserEvent.NoGamesDirectory -> snackbarHostState.showSnackbar(context.getString(R.string.details_no_folder))
            BrowserEvent.Unsupported -> snackbarHostState.showSnackbar(context.getString(R.string.browser_unsupported_link))
            is BrowserEvent.Failed -> snackbarHostState.showSnackbar(
                context.getString(R.string.details_start_failed, context.downloadErrorMessage(current.error)),
            )
        }
        viewModel.onEventHandled()
    }

    Column(Modifier.fillMaxSize().background(RShopColors.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConsoleButton(
                stringResource(R.string.browser_close),
                onClick = onClose,
                modifier = Modifier.focusRequester(closeFocus),
                style = ConsoleButtonStyle.Secondary,
            )
            if (state.tabCount > 1) {
                Spacer(Modifier.width(10.dp))
                ConsoleButton(
                    stringResource(R.string.browser_close_tab, state.tabIndex + 1, state.tabCount),
                    onClick = { browser?.closeCurrentTab() },
                    style = ConsoleButtonStyle.Secondary,
                )
            }
            // Escape hatch: the same page in the device's own browser (handles the download there).
            if (state.url.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                ConsoleButton(
                    stringResource(R.string.details_open_browser),
                    onClick = { CustomTabLauncher.open(context, state.url) },
                    style = ConsoleButtonStyle.Secondary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.title.ifEmpty { state.host },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.browser_hint, state.host),
                    style = MaterialTheme.typography.bodySmall,
                    color = RShopColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        state.blocked?.let { host ->
            BlockedBanner(host, onOpen = { browser?.acceptBlocked() }, onIgnore = { browser?.dismissBlocked() })
        }
        if (state.progress in 1..99) {
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
        } else {
            Spacer(Modifier.height(3.dp))
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                GeckoBrowser(ctx, viewModel.runtime, onState = { state = it }, onDownload = viewModel::onDownload).also {
                    it.open(viewModel.startUrl)
                    browser = it
                }
            },
            onRelease = { view ->
                view.destroy()
                browser = null
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}

/** A pop-up or redirect towards another site than the link clicked: shown only if the user wants. */
@Composable
private fun BlockedBanner(host: String, onOpen: () -> Unit, onIgnore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(RShopColors.SurfaceHigh).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.browser_blocked, host),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        ConsoleButton(stringResource(R.string.browser_blocked_ignore), onClick = onIgnore)
        Spacer(Modifier.width(8.dp))
        ConsoleButton(stringResource(R.string.browser_blocked_open), onClick = onOpen, style = ConsoleButtonStyle.Secondary)
    }
}
