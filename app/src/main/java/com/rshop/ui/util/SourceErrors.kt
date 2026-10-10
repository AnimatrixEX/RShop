package com.rshop.ui.util

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rshop.R
import com.rshop.data.sync.SourceError
import com.rshop.data.sync.SourceErrorKind
import java.time.Instant

/** Human explanation of a source failure; the technical detail goes to the logs, not the UI. */
@Composable
fun sourceErrorText(error: SourceError): String = LocalContext.current.sourceErrorText(error)

fun Context.sourceErrorText(error: SourceError): String = when (error.kind) {
    SourceErrorKind.Network -> getString(R.string.error_network)
    SourceErrorKind.AccessDenied -> getString(R.string.error_access_denied) + (error.detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    SourceErrorKind.Robots -> getString(R.string.error_robots)
    SourceErrorKind.Structure -> getString(R.string.error_structure)
    SourceErrorKind.Http -> getString(R.string.error_http, error.detail.orEmpty())
    SourceErrorKind.InvalidContent -> getString(R.string.error_invalid_content)
    SourceErrorKind.NoSource -> getString(R.string.error_no_source)
    SourceErrorKind.ApiKey -> getString(R.string.error_drive_key) + (error.detail?.let { " ($it)" } ?: "")
    SourceErrorKind.Quota -> quotaText(error.detail)
    SourceErrorKind.NoConsole -> getString(R.string.error_drive_no_console)
    SourceErrorKind.Unknown -> getString(R.string.error_unknown)
}

@Composable
fun relativeTime(instant: Instant): String {
    val now = System.currentTimeMillis()
    // DateUtils says "0 minutes ago" for the first minute.
    if (now - instant.toEpochMilli() < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.time_just_now)
    // Always used inside a sentence ("synced 2 minutes ago"): no leading capital.
    return DateUtils.getRelativeTimeSpanString(instant.toEpochMilli(), now, DateUtils.MINUTE_IN_MILLIS).toString()
        .replaceFirstChar { it.lowercase() }
}

/**
 * Google's two kinds of quota: the file's own (too many recent downloads by anybody, it lifts by itself)
 * and the API key's project (shared by everyone using the same key).
 */
fun android.content.Context.quotaText(reason: String?): String = when (reason) {
    "downloadQuotaExceeded" -> getString(R.string.error_quota_file)
    null, "" -> getString(R.string.error_quota)
    else -> getString(R.string.error_quota_project)
}
