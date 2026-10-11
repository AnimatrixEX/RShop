package com.rshop.scraper.remote

import java.io.Closeable
import java.io.InputStream
import java.time.Instant

/** One file or folder of a server. */
data class RemoteEntry(
    val name: String,
    /** Absolute path on the server. */
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val modifiedAt: Instant? = null,
)

/**
 * A file being read from a server. Closing it ends the transfer and the connection it used.
 * [startsAt] is where the stream really starts: 0 when the server could not resume at the offset asked.
 */
class RemoteFile(
    val input: InputStream,
    val sizeBytes: Long?,
    val modifiedAt: Instant?,
    val startsAt: Long,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() {
        try {
            input.close()
        } catch (_: Exception) {
        } finally {
            onClose()
        }
    }
}

/**
 * One connection to a server. Its calls block: callers run them off the main thread and never
 * share a session between threads. A session that reads a file ([open]) is used for nothing else.
 */
interface RemoteSession : Closeable {
    /** Direct children of the folder at [path]. */
    fun list(path: String): List<RemoteEntry>

    /** The file or folder at [path], null when it does not exist. */
    fun stat(path: String): RemoteEntry?

    /** Streams the file at [path] from [offset] (the actual start is in [RemoteFile.startsAt]). */
    fun open(path: String, offset: Long): RemoteFile
}

/** What a source needs to hand files to the downloader: a stream per file, on a connection of its own. */
interface RemoteFileProvider {
    suspend fun openFile(url: String, offset: Long): RemoteFile
}
