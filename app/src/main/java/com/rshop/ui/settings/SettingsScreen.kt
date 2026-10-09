package com.rshop.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.LocalContext
import com.rshop.data.update.AppRelease
import com.rshop.data.update.UpdateErrorKind
import com.rshop.data.update.UpdateState
import com.rshop.ui.util.formatSize
import com.rshop.ui.util.folderLabel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.rshop.ui.components.ControllerTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.focusRestorer
import com.rshop.domain.model.FREE_SPACE_CHOICES_MB
import com.rshop.domain.model.FocusStyle
import com.rshop.domain.model.CoverStyle
import com.rshop.domain.model.TextSize
import com.rshop.domain.model.ThemeAccent
import com.rshop.domain.model.ThemeBase
import com.rshop.ui.theme.ThemePalettes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.rshop.data.artwork.ArtworkStatus
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rshop.BuildConfig
import com.rshop.R
import com.rshop.data.preferences.AppLanguage
import com.rshop.data.storage.GamesFolder
import androidx.compose.runtime.remember
import androidx.compose.foundation.ExperimentalFoundationApi
import com.rshop.data.sync.SyncState
import com.rshop.ui.util.relativeTime
import com.rshop.ui.util.sourceErrorText
import com.rshop.ui.components.FocusableSurface
import com.rshop.ui.components.rememberInitialFocusRequester
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

@Composable
fun SettingsScreen(
    onOpenSourceSetup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onFolderAdded)
    }
    val folderSpace by viewModel.folderSpace.collectAsStateWithLifecycle()
    var folderActions by remember { mutableStateOf<GamesFolder?>(null) }
    // The last tab is remembered across rotation and navigation.
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = SettingsTab.entries[tabIndex.coerceIn(0, SettingsTab.entries.lastIndex)]
    val firstRowFocus = rememberInitialFocusRequester()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::onExportBackup)
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onBackupPicked)
    }
    val update by viewModel.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // "Install unknown apps" is a system screen; coming back continues the update if it was allowed.
    val allowInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onInstallPermissionResult()
    }

    BackupDialogs(backup, viewModel)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = 760.dp)) {
        SettingsTabs(selected = tab, onSelect = { tabIndex = it.ordinal }, firstFocus = firstRowFocus)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                SettingsTab.Catalog -> {
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_source),
                            subtitle = when (state.sources.size) {
                                0 -> stringResource(R.string.settings_source_none)
                                1 -> state.sources.first().let { "${it.name} · ${it.location}" }
                                else -> pluralStringResource(R.plurals.settings_sources_count, state.sources.size, state.sources.size) +
                                    " · " + state.sources.joinToString(", ") { it.name }
                            },
                            onClick = onOpenSourceSetup,
                        )
                    }
                    if (state.sources.isNotEmpty()) {
                        item {
                            val (subtitle, color) = syncSubtitle(state.sync)
                            SettingsRow(
                                // While running, the same row pauses (stops) the sync; games already read are kept.
                                title = stringResource(if (state.sync.running) R.string.settings_sync_pause else R.string.settings_sync_now),
                                subtitle = subtitle,
                                subtitleColor = color,
                                onClick = if (state.sync.running) viewModel::onPauseSync else viewModel::onSyncNow,
                                trailing = if (state.sync.running) {
                                    { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_read_ahead),
                            subtitle = stringResource(R.string.settings_read_ahead_desc),
                            onClick = { viewModel.onReadPagesAheadChange(!state.settings.readPagesAhead) },
                            trailing = { RShopSwitch(state.settings.readPagesAhead) },
                        )
                    }

                }
                SettingsTab.Storage -> {
                    item { GroupTitle(stringResource(R.string.settings_games_dirs)) }
                    if (state.folders.isEmpty()) {
                        item {
                            SettingsRow(
                                title = stringResource(R.string.settings_games_dir_none),
                                subtitle = stringResource(R.string.settings_games_dirs_help),
                                icon = painterResource(R.drawable.ic_folder),
                                onClick = { pickFolder.launch(null) },
                            )
                        }
                    } else {
                        items(state.folders, key = { it.uri.toString() }) { folder ->
                            FolderRow(
                                folder = folder,
                                freeBytes = folderSpace[folder.uri.toString()],
                                onClick = { folderActions = folder },
                            )
                        }
                        item {
                            Text(
                                stringResource(R.string.settings_games_dirs_help),
                                modifier = Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = RShopColors.TextTertiary,
                            )
                        }
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_games_dir_add),
                            subtitle = null,
                            icon = painterResource(R.drawable.ic_folder),
                            onClick = { pickFolder.launch(null) },
                        )
                    }
                    item {
                        ChoiceRow(
                            title = stringResource(R.string.settings_free_space),
                            options = (FREE_SPACE_CHOICES_MB + state.settings.minFreeSpaceMb).distinct().sorted(),
                            selected = state.settings.minFreeSpaceMb,
                            label = { megabytes -> if (megabytes >= 1024) "${megabytes / 1024} ${stringResource(R.string.unit_gb)}" else "$megabytes ${stringResource(R.string.unit_mb)}" },
                            swatch = null,
                            onSelect = viewModel::onMinFreeSpaceChange,
                        )
                    }
                }
                SettingsTab.Downloads -> {
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_wifi_only),
                            subtitle = stringResource(R.string.settings_wifi_only_desc),
                            onClick = { viewModel.onWifiOnlyChange(!state.settings.wifiOnly) },
                            trailing = { RShopSwitch(state.settings.wifiOnly) },
                        )
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_low_battery),
                            subtitle = stringResource(R.string.settings_low_battery_desc),
                            onClick = { viewModel.onPauseOnLowBatteryChange(!state.settings.pauseOnLowBattery) },
                            trailing = { RShopSwitch(state.settings.pauseOnLowBattery) },
                        )
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_delete_archives),
                            subtitle = stringResource(R.string.settings_delete_archives_desc),
                            onClick = { viewModel.onDeleteArchivesChange(!state.settings.deleteArchivesAfterInstall) },
                            trailing = { RShopSwitch(state.settings.deleteArchivesAfterInstall) },
                        )
                    }

                }
                SettingsTab.Images -> {
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_libretro),
                            subtitle = stringResource(R.string.settings_libretro_desc),
                            onClick = { viewModel.onLibretroChange(!state.artwork.libretroEnabled) },
                            trailing = { RShopSwitch(state.artwork.libretroEnabled) },
                        )
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_wikipedia),
                            subtitle = stringResource(R.string.settings_wikipedia_desc),
                            onClick = { viewModel.onMetadataChange(!state.artwork.metadataEnabled) },
                            trailing = { RShopSwitch(state.artwork.metadataEnabled) },
                        )
                    }
                    item {
                        SteamGridDbKeyForm(
                            status = state.artwork,
                            pending = state.pendingArtwork,
                            onSave = viewModel::onSaveSteamGridDbKey,
                        )
                    }

                }
                SettingsTab.Appearance -> {
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_language),
                            subtitle = stringResource(state.language.labelRes()),
                            onClick = viewModel::onCycleLanguage,
                        )
                    }
                    val theme = state.settings.theme
                    item {
                        ChoiceRow(
                            title = stringResource(R.string.settings_theme_base),
                            options = ThemeBase.entries,
                            selected = theme.base,
                            label = { stringResource(it.labelRes()) },
                            swatch = { ThemePalettes.swatch(it) },
                            // A style comes with the accent that suits it (red eShop, blue PS2); the accent can still be changed.
                            onSelect = { base -> viewModel.onThemeChange { t -> t.copy(base = base, accent = ThemePalettes.recommendedAccent(base) ?: t.accent) } },
                        )
                    }
                    item {
                        ChoiceRow(
                            title = stringResource(R.string.settings_theme_accent),
                            options = ThemeAccent.entries,
                            selected = theme.accent,
                            label = { stringResource(it.labelRes()) },
                            swatch = { ThemePalettes.accent(it).first },
                            onSelect = { accent -> viewModel.onThemeChange { t -> t.copy(accent = accent) } },
                        )
                    }
                    item {
                        ChoiceRow(
                            title = stringResource(R.string.settings_theme_focus),
                            options = FocusStyle.entries,
                            selected = theme.focus,
                            label = { stringResource(if (it == FocusStyle.White) R.string.theme_focus_white else R.string.theme_focus_accent) },
                            swatch = { if (it == FocusStyle.White) Color.White else RShopColors.AccentBright },
                            onSelect = { focus -> viewModel.onThemeChange { t -> t.copy(focus = focus) } },
                        )
                    }
                    item {
                        ChoiceRow(
                            title = stringResource(R.string.settings_text_size),
                            options = TextSize.entries,
                            selected = theme.textSize,
                            label = { stringResource(if (it == TextSize.Normal) R.string.text_size_normal else R.string.text_size_large) },
                            swatch = null,
                            onSelect = { size -> viewModel.onThemeChange { t -> t.copy(textSize = size) } },
                        )
                    }
                    item {
                        ChoiceRow(
                            title = stringResource(R.string.settings_cover_style),
                            options = CoverStyle.entries,
                            selected = theme.coverStyle,
                            label = { stringResource(if (it == CoverStyle.Flat) R.string.cover_style_flat else R.string.cover_style_3d) },
                            swatch = null,
                            onSelect = { style -> viewModel.onThemeChange { t -> t.copy(coverStyle = style) } },
                        )
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_animated_background),
                            subtitle = stringResource(R.string.settings_animated_background_desc),
                            onClick = { viewModel.onThemeChange { t -> t.copy(animatedBackground = !t.animatedBackground) } },
                            trailing = { RShopSwitch(theme.animatedBackground) },
                        )
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_dynamic_backdrop),
                            subtitle = stringResource(R.string.settings_dynamic_backdrop_desc),
                            onClick = { viewModel.onThemeChange { t -> t.copy(dynamicBackdrop = !t.dynamicBackdrop) } },
                            trailing = { RShopSwitch(theme.dynamicBackdrop) },
                        )
                    }

                }
                SettingsTab.App -> {
                    item { GroupTitle(stringResource(R.string.settings_backup)) }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_backup_export),
                            subtitle = stringResource(R.string.settings_backup_export_desc),
                            onClick = { exportBackup.launch(backupFileName()) },
                        )
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_backup_restore),
                            subtitle = stringResource(R.string.settings_backup_restore_desc),
                            onClick = { restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            trailing = if (backup.restoring) {
                                { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
                            } else {
                                null
                            },
                        )
                    }

                    item { GroupTitle(stringResource(R.string.settings_about)) }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_version),
                            subtitle = BuildConfig.VERSION_NAME,
                            onClick = null,
                        )
                    }
                    item {
                        SettingsRow(
                            title = stringResource(R.string.settings_auto_update),
                            subtitle = stringResource(R.string.settings_auto_update_desc),
                            onClick = { viewModel.onAutoCheckUpdatesChange(!state.settings.autoCheckUpdates) },
                            trailing = { RShopSwitch(state.settings.autoCheckUpdates) },
                        )
                    }
                    updateItems(
                        state = update,
                        onCheck = viewModel::onCheckUpdate,
                        onInstall = viewModel::onInstallUpdate,
                        onCancel = viewModel::onCancelUpdate,
                        onAllowInstall = {
                            allowInstall.launch(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                            )
                        },
                    )
                }
            }
        }
        }
    }

    folderActions?.let { folder ->
        FolderActionsDialog(
            folder = folder,
            onDefault = {
                viewModel.onFolderDefault(folder.uri)
                folderActions = null
            },
            onRemove = {
                viewModel.onFolderRemoved(folder.uri)
                folderActions = null
            },
            onDismiss = { folderActions = null },
        )
    }
}

private fun backupFileName(): String =
    "rshop-backup-${java.time.LocalDate.now().toString().replace("-", "")}.json"

/** Confirmation before a restore, and what happened after a save or a restore. */
@Composable
private fun BackupDialogs(state: BackupUiState, viewModel: SettingsViewModel) {
    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::onCancelRestore,
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (preview.createdAt > 0) {
                        Text(stringResource(R.string.backup_made_on, java.text.DateFormat.getDateInstance().format(java.util.Date(preview.createdAt))))
                    }
                    Text(
                        pluralStringResource(R.plurals.backup_sources, preview.sources.size, preview.sources.size) +
                            preview.sources.takeIf { it.isNotEmpty() }?.joinToString(prefix = " : ").orEmpty(),
                    )
                    Text(pluralStringResource(R.plurals.backup_favorites, preview.favorites, preview.favorites))
                    Text(pluralStringResource(R.plurals.backup_lists, preview.lists, preview.lists))
                    Text(stringResource(R.string.backup_restore_note), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextSecondary)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::onConfirmRestore) { Text(stringResource(R.string.backup_restore_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::onCancelRestore) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::onBackupMessageShown,
            title = {
                Text(
                    stringResource(
                        when (message) {
                            BackupMessage.Exported -> R.string.backup_saved_title
                            is BackupMessage.Restored -> R.string.backup_restored_title
                            is BackupMessage.Failed -> R.string.backup_failed_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    when (message) {
                        BackupMessage.Exported -> stringResource(R.string.backup_saved_body)
                        is BackupMessage.Restored -> stringResource(
                            R.string.backup_restored_body,
                            message.report.sources, message.report.favorites, message.report.lists, message.report.waiting,
                        )
                        is BackupMessage.Failed -> message.detail.ifEmpty { stringResource(R.string.backup_failed_body) }
                    },
                )
            },
            confirmButton = { TextButton(onClick = viewModel::onBackupMessageShown) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/** The "check for updates" row and, depending on where the update is, what it offers. */
private fun LazyListScope.updateItems(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (AppRelease) -> Unit,
    onCancel: () -> Unit,
    onAllowInstall: () -> Unit,
) {
    item {
        val spinner: @Composable () -> Unit = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
        when (state) {
            UpdateState.Idle -> SettingsRow(
                title = stringResource(R.string.update_check),
                subtitle = stringResource(R.string.update_check_desc),
                onClick = onCheck,
            )
            UpdateState.Checking -> SettingsRow(
                title = stringResource(R.string.update_checking),
                subtitle = null,
                onClick = null,
                trailing = spinner,
            )
            is UpdateState.UpToDate -> SettingsRow(
                title = stringResource(R.string.update_check),
                subtitle = stringResource(R.string.update_up_to_date, state.version),
                subtitleColor = RShopColors.Success,
                onClick = onCheck,
            )
            is UpdateState.Available -> SettingsRow(
                title = stringResource(R.string.update_available, state.release.version),
                subtitle = stringResource(R.string.update_install_desc, formatSize(state.release.sizeBytes)),
                subtitleColor = RShopColors.AccentBright,
                onClick = { onInstall(state.release) },
            )
            is UpdateState.Downloading -> {
                val total = state.release.sizeBytes
                val percent = (state.bytes * 100 / total).toInt().coerceIn(0, 100)
                SettingsRow(
                    title = stringResource(R.string.update_downloading, state.release.version, percent),
                    subtitle = stringResource(R.string.update_downloading_desc, formatSize(state.bytes), formatSize(total)),
                    subtitleColor = RShopColors.AccentBright,
                    onClick = onCancel,
                    trailing = {
                        CircularProgressIndicator(progress = { state.bytes.toFloat() / total }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    },
                )
            }
            is UpdateState.Verifying -> SettingsRow(
                title = stringResource(R.string.update_verifying),
                subtitle = null,
                onClick = null,
                trailing = spinner,
            )
            is UpdateState.Installing -> SettingsRow(
                title = stringResource(R.string.update_installing, state.release.version),
                subtitle = stringResource(R.string.update_installing_desc),
                subtitleColor = RShopColors.AccentBright,
                onClick = { onInstall(state.release) },
                trailing = spinner,
            )
            is UpdateState.Failed -> {
                val permission = state.kind == UpdateErrorKind.InstallPermission
                SettingsRow(
                    title = stringResource(if (permission) R.string.update_permission_title else R.string.update_failed),
                    subtitle = updateErrorText(state),
                    subtitleColor = RShopColors.Warning,
                    onClick = when {
                        permission -> onAllowInstall
                        state.release != null && state.kind != UpdateErrorKind.NotRShop -> ({ onInstall(state.release) })
                        else -> onCheck
                    },
                )
            }
        }
    }
    val release = (state as? UpdateState.Available)?.release
    if (release?.notes != null) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(RShopColors.Surface, Dimens.CardShape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(stringResource(R.string.update_notes), style = MaterialTheme.typography.labelMedium, color = RShopColors.AccentBright)
                Spacer(Modifier.height(4.dp))
                Text(release.notes, style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary, maxLines = 8)
            }
        }
    }
}

@Composable
private fun updateErrorText(state: UpdateState.Failed): String {
    val detail = state.detail?.let { " ($it)" }.orEmpty()
    return when (state.kind) {
        UpdateErrorKind.Network -> stringResource(R.string.update_error_network)
        UpdateErrorKind.Server -> stringResource(R.string.update_error_server) + detail
        UpdateErrorKind.NoRelease -> stringResource(R.string.update_error_no_release)
        UpdateErrorKind.BadRelease -> stringResource(R.string.update_error_bad_release) + detail
        UpdateErrorKind.Metered -> stringResource(R.string.update_error_metered)
        UpdateErrorKind.Corrupt -> stringResource(R.string.update_error_corrupt) + detail
        UpdateErrorKind.NotRShop -> stringResource(R.string.update_error_not_rshop)
        UpdateErrorKind.InstallPermission -> stringResource(R.string.update_permission_desc)
        UpdateErrorKind.InstallFailed -> stringResource(R.string.update_error_install) + detail
    }
}

@Composable
private fun syncSubtitle(sync: SyncState): Pair<String, Color> = when {
    sync.running && sync.section != null ->
        stringResource(R.string.sync_running_section, sync.section, sync.games) to RShopColors.AccentBright
    sync.running -> stringResource(R.string.sync_running, sync.games) to RShopColors.AccentBright
    sync.lastError != null -> stringResource(R.string.sync_failed, sourceErrorText(sync.lastError)) to RShopColors.Warning
    sync.lastSuccessAt != null -> stringResource(R.string.sync_last, relativeTime(sync.lastSuccessAt), sync.lastGameCount) to RShopColors.TextSecondary
    else -> stringResource(R.string.sync_never) to RShopColors.TextSecondary
}

private fun ThemeBase.labelRes(): Int = when (this) {
    ThemeBase.Night -> R.string.theme_base_night
    ThemeBase.Oled -> R.string.theme_base_oled
    ThemeBase.Slate -> R.string.theme_base_slate
    ThemeBase.Twilight -> R.string.theme_base_twilight
    ThemeBase.Glass -> R.string.theme_base_glass
    ThemeBase.Ps2 -> R.string.theme_base_ps2
    ThemeBase.Eshop -> R.string.theme_base_eshop
}

private fun ThemeAccent.labelRes(): Int = when (this) {
    ThemeAccent.Blue -> R.string.accent_blue
    ThemeAccent.Violet -> R.string.accent_violet
    ThemeAccent.Cyan -> R.string.accent_cyan
    ThemeAccent.Green -> R.string.accent_green
    ThemeAccent.Orange -> R.string.accent_orange
    ThemeAccent.Pink -> R.string.accent_pink
    ThemeAccent.Red -> R.string.accent_red
}

/**
 * One setting with a few visual choices: a row of chips, each one focus stop for controllers.
 * The chosen one is tinted with the accent; [swatch] previews the color it stands for.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    swatch: ((T) -> Color)?,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RShopColors.Surface, Dimens.CardShape)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = RShopColors.TextPrimary)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            // Room for the focus scale and ring, which would otherwise be clipped.
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(options) { option ->
                val isSelected = option == selected
                FocusableSurface(
                    onClick = { onSelect(option) },
                    shape = CircleShape,
                    focusedScale = 1.06f,
                    containerColor = if (isSelected) RShopColors.Accent.copy(alpha = 0.28f) else RShopColors.SurfaceHigh,
                    focusedContainerColor = if (isSelected) RShopColors.Accent.copy(alpha = 0.4f) else RShopColors.SurfaceHighest,
                    glow = false,
                ) {
                    Row(
                        Modifier.padding(start = if (swatch != null) 8.dp else 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (swatch != null) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .background(swatch(option), CircleShape)
                                    .border(1.dp, RShopColors.Outline, CircleShape),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            label(option),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) RShopColors.TextPrimary else RShopColors.TextSecondary,
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Text("✓", style = MaterialTheme.typography.labelLarge, color = RShopColors.AccentBright)
                        }
                    }
                }
            }
        }
    }
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.System -> R.string.language_system
    AppLanguage.French -> R.string.language_fr
    AppLanguage.English -> R.string.language_en
}

/** API key field: the key itself is never shown back, only its last characters. */
@Composable
private fun SteamGridDbKeyForm(status: ArtworkStatus, pending: Int, onSave: (String) -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val save = {
        focusManager.clearFocus()
        onSave(key)
        key = ""
    }
    val (subtitle, color) = when {
        status.invalidKey -> stringResource(R.string.settings_sgdb_invalid) to RShopColors.Error
        !status.keyConfigured -> stringResource(R.string.settings_sgdb_none) to RShopColors.TextTertiary
        pending > 0 -> pluralStringResource(R.plurals.settings_sgdb_pending, pending, status.keyHint.orEmpty(), pending) to RShopColors.AccentBright
        else -> stringResource(R.string.settings_sgdb_ok, status.keyHint.orEmpty()) to RShopColors.TextSecondary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RShopColors.Surface)
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.settings_sgdb_title), style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = color)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControllerTextField(shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { fieldModifier ->
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = fieldModifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_sgdb_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) save() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RShopColors.Focus,
                        unfocusedBorderColor = RShopColors.Outline,
                        focusedContainerColor = RShopColors.SurfaceHigh,
                        unfocusedContainerColor = RShopColors.Surface,
                    ),
                )
            }
            Spacer(Modifier.width(12.dp))
            ConsoleButton(stringResource(R.string.settings_sgdb_save), onClick = { if (key.isNotBlank()) save() })
            if (status.keyConfigured) {
                Spacer(Modifier.width(8.dp))
                ConsoleButton(
                    stringResource(R.string.settings_sgdb_remove),
                    onClick = { key = ""; onSave("") },
                    style = ConsoleButtonStyle.Secondary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.settings_sgdb_help), style = MaterialTheme.typography.bodySmall, color = RShopColors.TextTertiary)
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp, start = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = RShopColors.AccentBright,
    )
}

/** A whole-row target: big enough for touch, and one focus stop per setting for controllers. */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitleColor: Color = RShopColors.TextSecondary,
    icon: Painter? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = RShopColors.TextSecondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = RShopColors.TextPrimary)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                trailing()
            }
        }
    }
    if (onClick != null) {
        FocusableSurface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            focusedScale = 1.015f,
            containerColor = RShopColors.Surface,
            focusedContainerColor = RShopColors.SurfaceHigh,
            glow = false,
        ) { content() }
    } else {
        Box(modifier.fillMaxWidth()) { content() }
    }
}

/** Display-only switch: the whole row is the click target, so the switch itself is not focusable. */
@Composable
private fun RShopSwitch(checked: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        colors = SwitchDefaults.colors(
            checkedTrackColor = RShopColors.Accent,
            checkedThumbColor = RShopColors.OnAccent,
            uncheckedTrackColor = RShopColors.SurfaceHighest,
            uncheckedThumbColor = RShopColors.TextSecondary,
            uncheckedBorderColor = RShopColors.Outline,
        ),
    )
}

/** One games folder: where it is, whether it is the default one, and how much room is left. */
@Composable
private fun FolderRow(folder: GamesFolder, freeBytes: Long?, onClick: () -> Unit) {
    val notes = listOfNotNull(
        stringResource(R.string.folder_default).takeIf { folder.isDefault },
        stringResource(R.string.settings_games_dir_lost).takeIf { !folder.available },
        freeBytes?.let { stringResource(R.string.folder_free, formatSize(it)) },
    )
    SettingsRow(
        title = folderLabel(folder),
        subtitle = notes.joinToString(" - ").ifEmpty { null },
        subtitleColor = if (folder.available) RShopColors.TextSecondary else RShopColors.Warning,
        icon = painterResource(R.drawable.ic_folder),
        onClick = onClick,
    )
}
