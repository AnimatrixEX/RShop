package com.rshop.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object IntegrityVerifier {

    /** Streaming SHA-256, lower-case hex. */
    suspend fun sha256(file: File, onProgress: (Long) -> Unit = {}): String = withContext(Dispatchers.IO) { sha256OnIo(file, onProgress) }

    private suspend fun sha256OnIo(file: File, onProgress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var read = 0L
        FileInputStream(file).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                read += n
                onProgress(read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Throws [DownloadException.ChecksumMismatch] when the file does not match [expected]. */
    suspend fun verify(file: File, expected: String, onProgress: (Long) -> Unit = {}) {
        val actual = sha256(file, onProgress)
        if (!actual.equals(expected.trim(), ignoreCase = true)) {
            throw DownloadException.ChecksumMismatch(expected.lowercase(), actual)
        }
    }
}

/** What may be downloaded at all, before any byte is fetched. */
object DownloadPolicy {

    /** Executables and scripts are never downloaded, whatever the source says. */
    private val blockedExtensions = setOf(
        "apk", "apks", "xapk", "aab", "exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js",
        "jar", "dex", "dll", "so", "sh", "app", "dmg", "deb", "rpm", "html", "htm",
    )

    fun fileName(url: HttpUrl, fallback: String): String {
        val last = url.pathSegments.lastOrNull { it.isNotBlank() }
        return com.rshop.installation.SafeEntryPath.sanitizeName(last ?: fallback, fallback)
    }

    /**
     * Name announced by the server (Content-Disposition) first, else the URL. A name without
     * extension gets one from the content type when it is a known archive type.
     */
    fun fileName(serverName: String?, url: HttpUrl, fallback: String, contentType: String?): String {
        val fromUrl = url.pathSegments.lastOrNull { it.isNotBlank() }?.takeIf { '.' in it }
        val name = com.rshop.installation.SafeEntryPath.sanitizeName(serverName ?: fromUrl ?: fallback, fallback)
        val current = name.substringAfterLast('.', "")
        if (current.length in 1..5 && current.all(Char::isLetterOrDigit) && !current.all(Char::isDigit)) return name
        val extension = typeExtensions[contentType?.lowercase()] ?: return name
        return "$name.$extension"
    }

    private val typeExtensions = mapOf(
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/x-7z-compressed" to "7z",
        "application/x-tar" to "tar",
        "application/gzip" to "gz",
        "application/x-gzip" to "gz",
        "application/x-xz" to "xz",
    )

    /** Types that are never a game file, whatever the link or the file name says. */
    private val blockedTypes = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-executable",
        "application/x-sh",
        "application/javascript",
        "text/javascript",
    )

    fun checkContentType(contentType: String?) {
        if (contentType?.lowercase() in blockedTypes) throw DownloadException.Rejected("File type $contentType is not allowed")
    }

    /** A file the user picked themselves: only its type matters (no host, the user chose it). */
    fun checkLocalFile(fileName: String) {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in blockedExtensions) throw DownloadException.Rejected("File type .$extension is not allowed")
    }

    /** Throws [DownloadException.Rejected] with the reason when the download is not allowed. */
    fun check(url: HttpUrl, fileName: String, allowedHost: (HttpUrl) -> Boolean) {
        if (url.scheme != "https" && url.scheme != "http") throw DownloadException.Rejected("Unsupported URL scheme ${url.scheme}")
        if (!allowedHost(url)) throw DownloadException.Rejected("Host ${url.host} is not allowed for this source")
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in blockedExtensions) throw DownloadException.Rejected("File type .$extension is not allowed")
    }
}
