package com.rshop.domain.model

enum class DownloadStatus { Queued, Downloading, Paused, Verifying, Installing, Completed, Failed;

    val isActive: Boolean get() = this == Queued || this == Downloading || this == Verifying || this == Installing
}

enum class DownloadErrorKind {
    Network, Busy, AccessDenied, NotFound, Http, Html, Storage, TooLarge, Checksum, Rejected,
    NoDirectory, DirectoryLost, UnsafeArchive, Corrupt, Unsupported, NoLink, StreamLost, Quota, ApiKey, Unknown,
}

data class DownloadError(val kind: DownloadErrorKind, val detail: String? = null) {
    fun encode(): String = kind.name + (detail?.let { "|$it" } ?: "")

    companion object {
        fun decode(value: String?): DownloadError? {
            if (value.isNullOrBlank()) return null
            val kind = runCatching { DownloadErrorKind.valueOf(value.substringBefore('|')) }.getOrDefault(DownloadErrorKind.Unknown)
            return DownloadError(kind, value.substringAfter('|', "").ifEmpty { null })
        }
    }
}

data class DownloadTask(
    val gameId: String,
    val title: String,
    val platform: String?,
    val coverUrl: String?,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    /** Live values while running, null otherwise. */
    val bytesPerSecond: Long?,
    val etaSeconds: Long?,
    /** Bytes extracted so far while installing. */
    val installedBytes: Long?,
    val verified: Boolean,
    val hasChecksum: Boolean,
    val error: DownloadError?,
    /** The file being fetched, from 0, among [partCount] files of the game (discs, bin + cue). */
    val partIndex: Int = 0,
    val partCount: Int = 1,
) {
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}
