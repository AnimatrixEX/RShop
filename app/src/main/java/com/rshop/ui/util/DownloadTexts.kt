package com.rshop.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rshop.R
import com.rshop.domain.model.DownloadError
import com.rshop.domain.model.DownloadErrorKind
import com.rshop.domain.model.DownloadStatus
import com.rshop.domain.model.DownloadTask

@Composable
fun downloadErrorText(error: DownloadError): String = LocalContext.current.downloadErrorMessage(error)

/** "needed/available" bytes, as the error stores them, shown in a readable way. */
private fun Context.storageMessage(detail: String?): String {
    val (needed, available) = detail?.split('/')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 } ?: return getString(R.string.dl_error_storage)
    return getString(
        R.string.dl_error_storage_detail,
        android.text.format.Formatter.formatShortFileSize(this, needed),
        android.text.format.Formatter.formatShortFileSize(this, available.coerceAtLeast(0)),
    )
}

fun Context.downloadErrorMessage(error: DownloadError): String = when (error.kind) {
    DownloadErrorKind.Network -> getString(R.string.dl_error_network)
    DownloadErrorKind.Busy -> getString(R.string.dl_error_busy)
    DownloadErrorKind.AccessDenied -> getString(R.string.dl_error_access_denied)
    DownloadErrorKind.NotFound -> getString(R.string.dl_error_not_found)
    DownloadErrorKind.Http -> getString(R.string.dl_error_http, error.detail.orEmpty())
    DownloadErrorKind.Html -> getString(R.string.dl_error_html)
    DownloadErrorKind.Storage -> storageMessage(error.detail)
    DownloadErrorKind.TooLarge -> getString(R.string.dl_error_too_large)
    DownloadErrorKind.Checksum -> getString(R.string.dl_error_checksum)
    DownloadErrorKind.Rejected -> getString(R.string.dl_error_rejected, error.detail.orEmpty())
    DownloadErrorKind.NoDirectory -> getString(R.string.dl_error_no_directory)
    DownloadErrorKind.DirectoryLost -> getString(R.string.dl_error_directory_lost)
    DownloadErrorKind.UnsafeArchive -> getString(R.string.dl_error_unsafe_archive)
    DownloadErrorKind.Corrupt -> getString(R.string.dl_error_corrupt)
    DownloadErrorKind.Unsupported -> getString(R.string.dl_error_unsupported, error.detail.orEmpty())
    DownloadErrorKind.NoLink -> getString(R.string.dl_error_no_link)
    DownloadErrorKind.StreamLost -> getString(R.string.dl_error_stream_lost)
    DownloadErrorKind.Quota -> getString(R.string.error_quota)
    DownloadErrorKind.ApiKey -> getString(R.string.error_drive_key)
    DownloadErrorKind.Unknown -> getString(R.string.dl_error_unknown)
}

@Composable
fun downloadStatusText(status: DownloadStatus): String = stringResource(
    when (status) {
        DownloadStatus.Queued -> R.string.download_status_queued
        DownloadStatus.Downloading -> R.string.download_status_downloading
        DownloadStatus.Paused -> R.string.download_status_paused
        DownloadStatus.Verifying -> R.string.download_status_verifying
        DownloadStatus.Installing -> R.string.download_status_installing
        DownloadStatus.Completed -> R.string.download_status_completed
        DownloadStatus.Failed -> R.string.download_status_failed
    },
)

/** "12 Mo / 40 Mo · 2,1 Mo/s · reste 15 s" — only the parts that are known. */
@Composable
fun downloadProgressLine(task: DownloadTask): String {
    val parts = mutableListOf<String>()
    if (task.partCount > 1) parts += stringResource(R.string.download_part, task.partIndex + 1, task.partCount)
    if (task.status == DownloadStatus.Installing && task.installedBytes != null) {
        parts += stringResource(R.string.download_installed_bytes, formatSize(task.installedBytes))
    } else if (task.totalBytes != null) {
        parts += stringResource(R.string.download_bytes, formatSize(task.downloadedBytes), formatSize(task.totalBytes))
    } else if (task.downloadedBytes > 0) {
        parts += formatSize(task.downloadedBytes)
    }
    task.bytesPerSecond?.let { parts += stringResource(R.string.download_speed, formatSize(it)) }
    task.etaSeconds?.let { parts += stringResource(R.string.download_eta, formatDuration(it)) }
    return parts.joinToString(" · ")
}

fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min $s s"
        else -> "$s s"
    }
}
