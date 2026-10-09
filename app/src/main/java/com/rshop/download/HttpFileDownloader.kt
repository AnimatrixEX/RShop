package com.rshop.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cookies and User-Agent of the in-app browser that started a download. */
data class BrowserSession(val cookie: String?, val userAgent: String?)

/** Server validators remembered between attempts so a resumed download never mixes two files. */
data class ResumeValidators(val etag: String?, val lastModified: String?) {
    val ifRange: String? get() = etag?.takeUnless { it.startsWith("W/") } ?: lastModified
}

data class DownloadOutcome(val totalBytes: Long, val validators: ResumeValidators)

sealed class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** Worth retrying later: connection lost, timeout, 5xx. */
    class Transient(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    /** 429/503: the server is overloaded or limits simultaneous downloads; worth retrying later. */
    class Busy(val code: Int) : DownloadException("Server busy (HTTP $code)")
    class AccessDenied(val code: Int) : DownloadException("Access denied (HTTP $code)")
    class NotFound : DownloadException("File not found (HTTP 404)")
    class Http(val code: Int) : DownloadException("HTTP $code")

    /** The server answered with a web page (login, captcha, intermediate page) instead of a file. */
    class UnexpectedHtml : DownloadException("The server returned a web page instead of a file")
    class InsufficientStorage(val needed: Long, val available: Long) :
        DownloadException("Not enough space: $needed bytes needed, $available available")
    class TooLarge(val size: Long) : DownloadException("File too large: $size bytes")
    class ChecksumMismatch(val expected: String, val actual: String) :
        DownloadException("SHA-256 mismatch: expected $expected, got $actual")
    class Rejected(reason: String) : DownloadException(reason)

    /** A file handed over by the in-app browser can no longer be read (app restarted, cut off). */
    class StreamLost : DownloadException("The browser transfer was interrupted")
}

/**
 * Streams a file to disk with HTTP range resume. Never holds the file in memory; checks free
 * space and refuses HTML answers. Integrity (SHA-256) is checked by the caller afterwards.
 */
class HttpFileDownloader(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val maxFileSize: Long = 128L * 1024 * 1024 * 1024,
) {

    /**
     * Downloads [url] into [target], resuming from its current length when [validators] allow it.
     * [onStarted] gets the validators of this response (persist them for a later resume).
     */
    suspend fun download(
        url: HttpUrl,
        target: File,
        validators: ResumeValidators?,
        onStarted: suspend (ResumeValidators, Long?) -> Unit = { _, _ -> },
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        referer: String? = null,
        session: BrowserSession? = null,
        /** Space that must stay free once the file is on disk. */
        spaceMargin: Long = SPACE_MARGIN,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        // Blocking stream reads and file writes: never on the caller's thread.
        downloadOnIo(url, target, validators, onStarted, onProgress, referer, session, spaceMargin)
    }

    private suspend fun downloadOnIo(
        url: HttpUrl,
        target: File,
        validators: ResumeValidators?,
        onStarted: suspend (ResumeValidators, Long?) -> Unit,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
        referer: String?,
        session: BrowserSession?,
        spaceMargin: Long,
    ): DownloadOutcome {
        target.parentFile?.mkdirs()
        val existing = if (target.exists()) target.length() else 0L
        val resumeFrom = existing.takeIf { it > 0 && validators?.ifRange != null } ?: 0L

        val request = Request.Builder().url(url).header("User-Agent", session?.userAgent ?: userAgent).apply {
            if (referer != null) header("Referer", referer)
            session?.cookie?.let { header("Cookie", it) }
            if (resumeFrom > 0) {
                header("Range", "bytes=$resumeFrom-")
                header("If-Range", validators!!.ifRange!!)
            }
            // Byte-exact transfer: no transparent gzip that would break Range and sizes.
            header("Accept-Encoding", "identity")
        }.build()

        val response = try {
            execute(request)
        } catch (e: IOException) {
            throw DownloadException.Transient("Connection failed: ${e.message}", e)
        }
        response.use {
            val append = when (response.code) {
                206 -> {
                    val start = contentRangeStart(response)
                    if (start != resumeFrom) {
                        // The server resumed elsewhere: start over rather than corrupt the file.
                        target.delete()
                        return downloadOnIo(url, target, null, onStarted, onProgress, referer, session, spaceMargin)
                    }
                    true
                }
                200 -> false
                416 -> {
                    // Range not satisfiable: what we have is not a prefix of the current file.
                    target.delete()
                    if (resumeFrom > 0) return downloadOnIo(url, target, null, onStarted, onProgress, referer, session, spaceMargin)
                    throw DownloadException.Http(416)
                }
                401, 403 -> throw DownloadException.AccessDenied(response.code)
                404, 410 -> throw DownloadException.NotFound()
                429, 503 -> throw DownloadException.Busy(response.code)
                408, in 500..599 -> throw DownloadException.Transient("HTTP ${response.code}")
                else -> throw DownloadException.Http(response.code)
            }

            val type = response.body.contentType()
            if (type != null && type.type == "text" && type.subtype in setOf("html", "xhtml")) {
                throw DownloadException.UnexpectedHtml()
            }

            val bodyLength = response.body.contentLength().takeIf { it >= 0 }
            val total = when {
                append -> contentRangeTotal(response) ?: bodyLength?.let { it + resumeFrom }
                else -> bodyLength
            }
            if (total != null && total > maxFileSize) throw DownloadException.TooLarge(total)
            val remaining = bodyLength ?: 0L
            val available = target.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (remaining + spaceMargin > available) {
                throw DownloadException.InsufficientStorage(remaining + spaceMargin, available)
            }

            val newValidators = ResumeValidators(response.header("ETag"), response.header("Last-Modified"))
            onStarted(newValidators, total)

            var downloaded = if (append) resumeFrom else 0L
            try {
                FileOutputStream(target, append).use { output ->
                    val source = response.body.byteStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    onProgress(downloaded, total)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
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

            if (total != null && downloaded != total) {
                throw DownloadException.Transient("Incomplete transfer: $downloaded of $total bytes")
            }
            return DownloadOutcome(downloaded, newValidators)
        }
    }

    private fun contentRangeStart(response: Response): Long? =
        CONTENT_RANGE.find(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    private fun contentRangeTotal(response: Response): Long? =
        CONTENT_RANGE.find(response.header("Content-Range").orEmpty())?.groupValues?.get(3)?.toLongOrNull()

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, _, _ -> response.close() }
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        })
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val SPACE_MARGIN = 50L * 1024 * 1024
        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
    }
}
