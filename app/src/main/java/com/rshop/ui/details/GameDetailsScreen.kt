package com.rshop.ui.details

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.rshop.ui.browser.CustomTabLauncher
import com.rshop.ui.util.downloadErrorMessage
import com.rshop.ui.util.sourceErrorText
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.rshop.R
import com.rshop.domain.model.Game
import com.rshop.ui.components.Backdrop
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.EmptyState
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.components.GameCover
import com.rshop.ui.components.SectionHeader
import com.rshop.ui.components.rememberInitialFocusRequester
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.formatCount
import com.rshop.ui.util.formatDate
import com.rshop.ui.util.formatSize

@Composable
fun GameDetailsScreen(
    onBack: () -> Unit,
    onOpenBrowser: (gameId: String, url: String) -> Unit,
    viewModel: GameDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onFolderPicked)
    }
    // Asked once, on the first install; downloads work without it (no progress notification).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val install = { optionUrl: String? ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.onInstall(optionUrl)
    }
    // Picks a file the user downloaded themselves (e.g. in the device browser) and installs it.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val (name, size) = queryFile(context, uri)
        viewModel.onInstallLocalFile(uri, name, size)
    }
    // Set while the player picks one of several files (formats, discs…).
    var chooser by remember { mutableStateOf<Game?>(null) }

    LaunchedEffect(event) {
        when (val current = event) {
            null -> return@LaunchedEffect
            DetailsEvent.PickFolder -> {
                snackbarHostState.showSnackbar(context.getString(R.string.details_no_folder))
                pickFolder.launch(null)
            }
            is DetailsEvent.StartFailed -> snackbarHostState.showSnackbar(
                context.getString(R.string.details_start_failed, context.downloadErrorMessage(current.error)),
            )
            is DetailsEvent.OpenBrowser -> onOpenBrowser(current.gameId, current.url)
            is DetailsEvent.Deleted -> snackbarHostState.showSnackbar(context.getString(R.string.library_deleted, current.title))
            is DetailsEvent.DeleteFailed -> snackbarHostState.showSnackbar(context.getString(R.string.library_delete_failed, current.title))
        }
        viewModel.onEventHandled()
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            GameDetailsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            GameDetailsUiState.NotFound -> EmptyState(
                icon = painterResource(R.drawable.ic_library),
                title = stringResource(R.string.details_not_found),
                body = "",
                action = { ConsoleButton(stringResource(R.string.cd_back), onClick = onBack) },
            )
            is GameDetailsUiState.Loaded -> GameDetailsContent(
                state = current,
                onBack = onBack,
                onInstall = {
                    if (current.game.downloadOptions.size > 1) chooser = current.game else install(null)
                },
                onOpenBrowser = {
                    val url = current.browserUrl
                    // The real device browser, over RShop. If none can open it, fall back to the
                    // in-app browser (still useful for sites that do not block it).
                    if (url == null || !CustomTabLauncher.open(context, url)) {
                        viewModel.onInstall(null)
                    }
                },
                onInstallFile = { pickFile.launch(DOWNLOAD_MIME_TYPES) },
                onPause = { viewModel.onPause() },
                onResume = { viewModel.onResume() },
                onCancel = { viewModel.onCancel() },
                onUninstall = viewModel::onUninstall,
                onToggleFavorite = viewModel::onToggleFavorite,
            )
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
    chooser?.let { game ->
        FormatChooserDialog(
            title = game.title,
            options = game.downloadOptions,
            onPick = { option ->
                chooser = null
                install(option.url)
            },
            onDismiss = { chooser = null },
        )
    }
}

@Composable
private fun GameDetailsContent(
    state: GameDetailsUiState.Loaded,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onOpenBrowser: () -> Unit,
    onInstallFile: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val game = state.game
    val primaryFocus = rememberInitialFocusRequester()

    Box(Modifier.fillMaxSize()) {
        Backdrop(highlighted = game)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
        ) {
            FocusableSurface(
                onClick = onBack,
                shape = CircleShape,
                containerColor = RShopColors.SurfaceHigh,
                focusedContainerColor = RShopColors.SurfaceHighest,
                glow = false,
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(horizontal = Dimens.ScreenPadding)
                    .size(44.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                GameCover(
                    game = game,
                    modifier = Modifier
                        .width(190.dp)
                        .aspectRatio(Dimens.CoverAspectRatio)
                        .shadow(24.dp, Dimens.CardShape)
                        .clip(Dimens.CardShape),
                )
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    game.platform?.let {
                        Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = RShopColors.AccentBright)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(game.title, style = MaterialTheme.typography.headlineMedium, color = RShopColors.TextPrimary)
                    Spacer(Modifier.height(16.dp))
                    MetadataRow(game, state.sourceName)
                    if (state.refreshing) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.details_reading_page), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextSecondary)
                        }
                    }
                    state.refreshError?.let { error ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.details_refresh_failed, sourceErrorText(error)),
                            style = MaterialTheme.typography.bodySmall,
                            color = RShopColors.Warning,
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    InstallPanel(
                        state = state,
                        primaryFocus = primaryFocus,
                        onInstall = onInstall,
                        onOpenBrowser = onOpenBrowser,
                        onInstallFile = onInstallFile,
                        onPause = onPause,
                        onResume = onResume,
                        onCancel = onCancel,
                        onUninstall = onUninstall,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }

            game.description?.let { description ->
                Spacer(Modifier.height(Dimens.SectionSpacing))
                SectionHeader(stringResource(R.string.details_description))
                Spacer(Modifier.height(10.dp))
                ExpandableDescription(description, Modifier.padding(horizontal = Dimens.ScreenPadding))
            }

            if (game.screenshots.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.SectionSpacing))
                SectionHeader(stringResource(R.string.details_screenshots))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
                ) {
                    items(game.screenshots) { url ->
                        FocusableSurface(onClick = {}, modifier = Modifier.height(150.dp).aspectRatio(16f / 9f)) {
                            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(game: Game, sourceName: String?) {
    val unknown = stringResource(R.string.details_unknown)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetadataItem(stringResource(R.string.details_genre), game.genre ?: unknown)
        MetadataItem(stringResource(R.string.details_version), game.version ?: unknown)
        MetadataItem(stringResource(R.string.details_size), game.sizeBytes?.let { formatSize(it) } ?: unknown)
        MetadataItem(stringResource(R.string.details_updated), game.updatedAt?.let { formatDate(it) } ?: unknown)
        game.downloadCount?.let { MetadataItem(stringResource(R.string.details_downloads), formatCount(it)) }
        sourceName?.let { MetadataItem(stringResource(R.string.details_source), it) }
    }
}

@Composable
private fun MetadataItem(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = RShopColors.TextTertiary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = RShopColors.TextPrimary)
    }
}

/** Focusable so a controller can scroll down to it; pressing it expands the full text. */
@Composable
private fun ExpandableDescription(text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    FocusableSurface(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
        focusedScale = 1.01f,
        containerColor = RShopColors.Surface.copy(alpha = 0.6f),
        focusedContainerColor = RShopColors.SurfaceHigh,
        glow = false,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            style = MaterialTheme.typography.bodyLarge,
            color = RShopColors.TextPrimary.copy(alpha = 0.9f),
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Any file type: ROMs and archives carry inconsistent MIME types; executables are refused on install. */
private val DOWNLOAD_MIME_TYPES = arrayOf("*/*")

/** Display name and size of a picked file, from the content provider. */
private fun queryFile(context: android.content.Context, uri: android.net.Uri): Pair<String?, Long?> {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to null
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            val name = nameIndex.takeIf { it >= 0 }?.let { cursor.getString(it) }
            val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) }
            name to size
        } ?: (null to null)
    }.getOrDefault(null to null)
}
