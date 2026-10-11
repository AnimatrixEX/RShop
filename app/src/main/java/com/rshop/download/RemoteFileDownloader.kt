package com.rshop.download

import com.rshop.scraper.remote.RemoteFile
import com.rshop.scraper.remote.RemoteFileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Streams a file of an FTP, FTPS or SFTP server to disk, resuming a partial file when the server's
 * copy is still the same one (same size and date). Like [HttpFileDownloader] it never holds the file
 * in memory and checks free space first; failures to reach or read the server propagate as the
 * scraper's exceptions, which [toDownloadError] explains.
 */
class RemoteFileDownloader(private val maxFileSize: Long = 128L * 1024 * 1024 * 1024) {

    /**
     * Downloads [url] into [target]. [validator] is what [onStarted] reported for the partial file on
     * disk ("size:date"), null for a fresh start.
     */
    suspend fun download(
        provider: RemoteFileProvider,
        url: String,
        target: File,
        validator: String?,
        onStarted: suspend (validator: String, total: Long?) -> Unit = { _, _ -> },
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        /** Space that must stay free once the file is on disk. */
        spaceMargin: Long = SPACE_MARGIN,
    ): DownloadOutcome {
        target.parentFile?.mkdirs()
        var resumeFrom = if (target.exists() && validator != null) target.length() else 0L
        while (true) {
            val remote = provider.openFile(url, resumeFrom)
            val outcome = remote.use { copy(it, target, resumeFrom, validator, onStarted, onProgress, spaceMargin) }
            // null: the file changed on the server since the partial copy, which is thrown away.
            if (outcome != null) return outcome
            target.delete()
            resumeFrom = 0
        }
    }

    private suspend fun copy(
        remote: RemoteFile,
        target: File,
        requested: Long,
        validator: String?,
        onStarted: suspend (String, Long?) -> Unit,
        onProgress: suspend (Long, Long?) -> Unit,
        spaceMargin: Long,
    ): DownloadOutcome? = withContext(Dispatchers.IO) {
        val total = remote.sizeBytes
        val current = validatorOf(remote)
        if (requested > 0) {
            // A partial copy only continues the same file: another size or date means start over.
            if (current != validator || (total != null && requested > total)) return@withContext null
        }
        // A server that could not resume at [requested] sends the file from its start.
        val append = remote.startsAt > 0
        if (!append && target.exists()) target.delete()
        if (total != null && total > maxFileSize) throw DownloadException.TooLarge(total)
        val remaining = if (total != null) total - remote.startsAt else 0L
        val available = target.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (remaining + spaceMargin > available) throw DownloadException.InsufficientStorage(remaining + spaceMargin, available)

        onStarted(current, total)
        var downloaded = remote.startsAt
        try {
            FileOutputStream(target, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                onProgress(downloaded, total)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = remote.input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (downloaded > maxFileSize) throw DownloadException.TooLarge(downloaded)
                    onProgress(downloaded, total)
                }
            }
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            // Partial data stays on disk: the next attempt resumes from there.
            throw DownloadException.Transient("Transfer interrupted: ${e.message}", e)
        }
        if (total != null && downloaded != total) throw DownloadException.Transient("Incomplete transfer: $downloaded of $total bytes")
        DownloadOutcome(downloaded, ResumeValidators(current, null))
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val SPACE_MARGIN = 50L * 1024 * 1024

        fun validatorOf(remote: RemoteFile) = "${remote.sizeBytes ?: -1}:${remote.modifiedAt?.epochSecond ?: 0}"
    }
}
