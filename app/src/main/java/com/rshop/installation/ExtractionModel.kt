package com.rshop.installation

import java.io.IOException
import java.io.OutputStream

/** Where extracted entries go: the user's SAF folder in the app, a plain directory in tests. */
interface ExtractionSink {
    /** Creates (or reuses) a directory; [path] is already validated by [SafeEntryPath]. */
    fun directory(path: List<String>)

    /** Opens a new file for writing, creating parent directories as needed. */
    fun file(path: List<String>): OutputStream
}

data class ExtractionLimits(
    /** Hard cap on bytes written, protects against decompression bombs and full disks. */
    val maxTotalBytes: Long,
    val maxEntries: Int = 100_000,
)

data class ExtractionResult(
    val bytesWritten: Long,
    val files: Int,
    /** Distinct first path segments, used to decide the final layout. */
    val topLevelNames: Set<String>,
    /** Distinct file formats written, most common first ("GBA", "BIN + CUE"); see [FileFormats]. */
    val formats: List<String> = emptyList(),
)

sealed class InstallException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class UnsafeEntry(val entry: String) : InstallException("Unsafe archive entry rejected: $entry")
    class TooLarge(val limit: Long) : InstallException("Archive expands beyond $limit bytes")
    class TooManyEntries(val limit: Int) : InstallException("Archive has more than $limit entries")
    class Corrupt(cause: Throwable) : InstallException("Archive is damaged or unsupported: ${cause.message}", cause)
    class Empty : InstallException("Archive contains no file")
    class UnsupportedFormat(val format: String) : InstallException("$format archives are not supported")
    class NoGamesDirectory : InstallException("No games folder selected")
    class DirectoryLost : InstallException("Access to the games folder was lost")
    class Storage(message: String, cause: Throwable? = null) : InstallException(message, cause)
}
