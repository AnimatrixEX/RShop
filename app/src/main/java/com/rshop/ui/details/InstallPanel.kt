package com.rshop.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rshop.R
import com.rshop.domain.model.DownloadStatus
import com.rshop.domain.model.DownloadTask
import com.rshop.domain.model.InstalledGame
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.ConsoleIconButton
import com.rshop.ui.components.FavoriteButton
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.downloadErrorText
import com.rshop.ui.util.downloadProgressLine
import com.rshop.ui.util.downloadStatusText

/** Install / progress / installed actions of a game page. */
@Composable
fun InstallPanel(
    state: GameDetailsUiState.Loaded,
    primaryFocus: FocusRequester,
    onInstall: () -> Unit,
    onOpenBrowser: () -> Unit,
    onInstallFile: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToList: () -> Unit,
) {
    val download = state.download?.takeIf { it.status != DownloadStatus.Completed }
    var confirmDelete by remember { mutableStateOf(false) }

    // The favorite toggle ends the main action row, like on the Home hero.
    val favorite: @Composable () -> Unit = {
        FavoriteButton(isFavorite = state.isFavorite, onToggle = onToggleFavorite)
        ConsoleIconButton(
            icon = Icons.AutoMirrored.Filled.List,
            contentDescription = stringResource(R.string.list_picker_title),
            onClick = onAddToList,
        )
    }
    Column(Modifier.widthIn(max = 560.dp)) {
        when {
            download != null -> DownloadProgress(download, primaryFocus, onPause, onResume, onCancel, favorite)
            state.installed != null -> Installed(state.installed, primaryFocus, onUpdate = onInstall, onDelete = { confirmDelete = true }, favorite)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ConsoleButton(
                        text = stringResource(R.string.action_install),
                        icon = painterResource(R.drawable.ic_download),
                        onClick = onInstall,
                        modifier = Modifier.focusRequester(primaryFocus),
                    )
                    favorite()
                }
                // Behind a site page RShop cannot pass (bot checks, logins): open the real browser
                // to get the file, then install it from where it was saved.
                if (state.browserUrl != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConsoleButton(stringResource(R.string.details_open_browser), onOpenBrowser, style = ConsoleButtonStyle.Secondary)
                        ConsoleButton(stringResource(R.string.details_install_file), onInstallFile, style = ConsoleButtonStyle.Secondary)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.library_confirm_delete, state.game.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onUninstall()
                }) { Text(stringResource(R.string.action_uninstall)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = RShopColors.SurfaceHigh,
        )
    }
}

@Composable
private fun DownloadProgress(
    task: DownloadTask,
    focus: FocusRequester,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Text(downloadStatusText(task.status), style = MaterialTheme.typography.titleMedium, color = RShopColors.TextPrimary)
    Spacer(Modifier.height(8.dp))
    val progress = task.progress
    val barModifier = Modifier
        .fillMaxWidth()
        .height(8.dp)
        .clip(RoundedCornerShape(4.dp))
    if (progress != null && task.status != DownloadStatus.Installing && task.status != DownloadStatus.Verifying) {
        LinearProgressIndicator(progress = { progress }, modifier = barModifier, color = RShopColors.Accent, trackColor = RShopColors.SurfaceHighest)
    } else if (task.status.isActive) {
        LinearProgressIndicator(modifier = barModifier, color = RShopColors.Accent, trackColor = RShopColors.SurfaceHighest)
    }
    Spacer(Modifier.height(6.dp))
    val line = downloadProgressLine(task)
    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = RShopColors.TextSecondary)
    task.error?.let {
        Spacer(Modifier.height(4.dp))
        Text(downloadErrorText(it), style = MaterialTheme.typography.bodyMedium, color = RShopColors.Warning)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        when (task.status) {
            DownloadStatus.Paused -> ConsoleButton(stringResource(R.string.action_resume), onResume, Modifier.focusRequester(focus))
            DownloadStatus.Failed -> ConsoleButton(stringResource(R.string.action_retry), onResume, Modifier.focusRequester(focus))
            DownloadStatus.Queued, DownloadStatus.Downloading ->
                ConsoleButton(stringResource(R.string.action_pause), onPause, Modifier.focusRequester(focus), style = ConsoleButtonStyle.Secondary)
            else -> Unit
        }
        if (task.status != DownloadStatus.Installing) {
            ConsoleButton(stringResource(R.string.action_cancel), onCancel, style = ConsoleButtonStyle.Secondary)
        }
        trailing()
    }
}

@Composable
private fun Installed(
    installed: InstalledGame,
    focus: FocusRequester,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = RShopColors.Success, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            text = installed.installedVersion?.let { stringResource(R.string.details_installed, it) }
                ?: stringResource(R.string.details_installed_noversion),
            style = MaterialTheme.typography.titleMedium,
            color = RShopColors.TextPrimary,
        )
    }
    if (installed.updateAvailable) {
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.details_update_available, installed.catalogVersion.orEmpty()), color = RShopColors.AccentBright)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (installed.updateAvailable) {
            ConsoleButton(stringResource(R.string.action_update), onUpdate, Modifier.focusRequester(focus))
            ConsoleButton(stringResource(R.string.action_uninstall), onDelete, style = ConsoleButtonStyle.Secondary)
        } else {
            ConsoleButton(stringResource(R.string.action_uninstall), onDelete, Modifier.focusRequester(focus), style = ConsoleButtonStyle.Secondary)
        }
        trailing()
    }
}
