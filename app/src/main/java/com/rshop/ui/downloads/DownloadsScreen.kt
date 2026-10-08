package com.rshop.ui.downloads

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rshop.R
import com.rshop.domain.model.DownloadStatus
import com.rshop.domain.model.DownloadTask
import com.rshop.ui.components.ConsoleButton
import com.rshop.ui.components.ConsoleButtonStyle
import com.rshop.ui.components.EmptyState
import com.rshop.ui.components.GameCover
import com.rshop.ui.components.coverGame
import com.rshop.ui.components.rememberInitialFocusRequester
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors
import com.rshop.ui.util.downloadErrorText
import com.rshop.ui.util.downloadProgressLine
import com.rshop.ui.util.downloadStatusText

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val list = tasks ?: return
    if (list.isEmpty()) {
        EmptyState(
            icon = painterResource(R.drawable.ic_download),
            title = stringResource(R.string.downloads_empty_title),
            body = stringResource(R.string.downloads_empty_body),
        )
        return
    }

    val firstFocus = rememberInitialFocusRequester(ready = list.isNotEmpty())
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 980.dp),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.any { it.status == DownloadStatus.Completed }) {
                item(key = "clear") {
                    ConsoleButton(stringResource(R.string.action_clear_finished), viewModel::clearFinished, style = ConsoleButtonStyle.Secondary)
                }
            }
            itemsIndexed(list, key = { _, task -> task.gameId }) { index, task ->
                DownloadRow(
                    task = task,
                    focus = if (index == 0) firstFocus else null,
                    onPause = { viewModel.pause(task.gameId) },
                    onResume = { viewModel.resume(task.gameId) },
                    onCancel = { viewModel.cancel(task.gameId) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    task: DownloadTask,
    focus: FocusRequester?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RShopColors.Surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameCover(
            game = coverGame(task.gameId, task.title, task.platform, task.coverUrl),
            showTitle = false,
            modifier = Modifier
                .width(66.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(task.platform, downloadStatusText(task.status)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.status == DownloadStatus.Failed) RShopColors.Warning else RShopColors.TextSecondary,
                )
                if (task.verified) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = RShopColors.Success, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.download_verified), style = MaterialTheme.typography.bodySmall, color = RShopColors.Success)
                }
            }
            if (task.status != DownloadStatus.Completed) {
                Spacer(Modifier.height(8.dp))
                val barModifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                val progress = task.progress
                if (progress != null && task.status != DownloadStatus.Installing && task.status != DownloadStatus.Verifying) {
                    LinearProgressIndicator(progress = { progress }, modifier = barModifier, color = RShopColors.Accent, trackColor = RShopColors.SurfaceHighest)
                } else if (task.status.isActive) {
                    LinearProgressIndicator(modifier = barModifier, color = RShopColors.Accent, trackColor = RShopColors.SurfaceHighest)
                }
                val line = downloadProgressLine(task)
                if (line.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(line, style = MaterialTheme.typography.bodySmall, color = RShopColors.TextSecondary)
                }
            }
            task.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(downloadErrorText(it), style = MaterialTheme.typography.bodySmall, color = RShopColors.Warning)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            val primaryModifier = if (focus != null) Modifier.focusRequester(focus) else Modifier
            when (task.status) {
                DownloadStatus.Queued, DownloadStatus.Downloading ->
                    ConsoleButton(stringResource(R.string.action_pause), onPause, primaryModifier, style = ConsoleButtonStyle.Secondary)
                DownloadStatus.Paused -> ConsoleButton(stringResource(R.string.action_resume), onResume, primaryModifier)
                DownloadStatus.Failed -> ConsoleButton(stringResource(R.string.action_retry), onResume, primaryModifier)
                else -> Unit
            }
            if (task.status != DownloadStatus.Installing && task.status != DownloadStatus.Verifying) {
                ConsoleButton(
                    stringResource(if (task.status == DownloadStatus.Completed) R.string.action_close else R.string.action_cancel),
                    onCancel,
                    if (task.status == DownloadStatus.Completed) primaryModifier else Modifier,
                    style = ConsoleButtonStyle.Secondary,
                )
            }
        }
    }
}
