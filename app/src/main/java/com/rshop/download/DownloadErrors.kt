package com.rshop.download

import com.rshop.domain.model.DownloadError
import com.rshop.domain.model.DownloadErrorKind
import com.rshop.installation.InstallException
import com.rshop.scraper.ScraperException
import java.io.IOException

fun Throwable.toDownloadError(): DownloadError = when (this) {
    is DownloadException.Transient -> DownloadError(DownloadErrorKind.Network, message)
    is DownloadException.AccessDenied -> DownloadError(DownloadErrorKind.AccessDenied, "HTTP $code")
    is DownloadException.NotFound -> DownloadError(DownloadErrorKind.NotFound)
    is DownloadException.Http -> DownloadError(DownloadErrorKind.Http, "HTTP $code")
    is DownloadException.UnexpectedHtml -> DownloadError(DownloadErrorKind.Html)
    is DownloadException.InsufficientStorage -> DownloadError(DownloadErrorKind.Storage, "$needed/$available")
    is DownloadException.TooLarge -> DownloadError(DownloadErrorKind.TooLarge)
    is DownloadException.ChecksumMismatch -> DownloadError(DownloadErrorKind.Checksum)
    is DownloadException.StreamLost -> DownloadError(DownloadErrorKind.StreamLost)
    is DownloadException.Rejected -> DownloadError(DownloadErrorKind.Rejected, message)
    is InstallException.NoGamesDirectory -> DownloadError(DownloadErrorKind.NoDirectory)
    is InstallException.DirectoryLost -> DownloadError(DownloadErrorKind.DirectoryLost)
    is InstallException.UnsafeEntry, is InstallException.TooLarge, is InstallException.TooManyEntries ->
        DownloadError(DownloadErrorKind.UnsafeArchive, message)
    is InstallException.UnsupportedFormat -> DownloadError(DownloadErrorKind.Unsupported, format)
    is InstallException.Corrupt, is InstallException.Empty -> DownloadError(DownloadErrorKind.Corrupt, message)
    is InstallException.Storage -> DownloadError(DownloadErrorKind.Storage, message)
    is ScraperException.Network -> DownloadError(DownloadErrorKind.Network, message)
    is ScraperException.AccessDenied -> DownloadError(DownloadErrorKind.AccessDenied, "HTTP $code")
    is ScraperException -> DownloadError(DownloadErrorKind.NoLink, message)
    is IOException -> DownloadError(DownloadErrorKind.Network, message)
    else -> DownloadError(DownloadErrorKind.Unknown, message)
}

/** Errors a later automatic retry can fix. */
val DownloadError.isTransient: Boolean get() = kind == DownloadErrorKind.Network
