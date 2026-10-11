package com.rshop.scraper.remote

import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.FtpConfig
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.IOException
import java.time.Duration
import javax.net.ssl.HttpsURLConnection

/** FTP and FTPS (explicit TLS) through Apache Commons Net, in passive mode and binary type. */
internal class FtpSession private constructor(
    private val client: FTPClient,
    private val url: RemoteUrl,
) : RemoteSession {

    /** Whether the server knows MLSD (exact types and sizes); null until tried. */
    private var mlsd: Boolean? = null

    override fun list(path: String): List<RemoteEntry> = try {
        listing(path).filter { it.name != null && it.name != "." && it.name != ".." && it.name.isNotEmpty() && '/' !in it.name }
            .mapNotNull { file ->
                val full = join(path, file.name)
                val directory = when {
                    file.isDirectory -> true
                    file.isSymbolicLink -> isFolder(full)
                    file.isFile -> false
                    else -> return@mapNotNull null
                }
                RemoteEntry(file.name, full, directory, if (directory) null else file.size.takeIf { it >= 0 }, file.timestampInstant)
            }
    } catch (e: IOException) {
        throw ScraperException.Network(url.withPath(path).toString(), e)
    }

    override fun stat(path: String): RemoteEntry? = try {
        val name = path.trimEnd('/').substringAfterLast('/')
        when {
            path == "/" -> RemoteEntry("/", "/", true)
            else -> statEntry(path, name)
        }
    } catch (e: IOException) {
        throw ScraperException.Network(url.withPath(path).toString(), e)
    }

    private fun statEntry(path: String, name: String): RemoteEntry? {
        val mlst = if (mlsd != false) client.mlistFile(path) else null
        if (mlst != null && FTPReply.isPositiveCompletion(client.replyCode)) {
            return RemoteEntry(name, path, mlst.isDirectory, if (mlst.isDirectory) null else mlst.size.takeIf { it >= 0 }, mlst.timestampInstant)
        }
        if (isFolder(path)) return RemoteEntry(name, path, true)
        val size = client.getSize(path)?.trim()?.toLongOrNull()
        if (size == null || !FTPReply.isPositiveCompletion(client.replyCode)) return null
        val modified = client.getModificationTime(path)?.let { runCatching { parseMdtm(it) }.getOrNull() }
        return RemoteEntry(name, path, false, size, modified)
    }

    override fun open(path: String, offset: Long): RemoteFile {
        val where = url.withPath(path).toString()
        try {
            val entry = stat(path) ?: throw ScraperException.Http(where, 404)
            if (entry.isDirectory) throw ScraperException.Http(where, 404)
            var start = offset
            client.restartOffset = start
            var input = client.retrieveFileStream(path)
            if (input == null && start > 0) {
                // The server cannot resume: send the file from its beginning, the caller notices.
                start = 0
                client.restartOffset = 0
                input = client.retrieveFileStream(path)
            }
            input ?: throw refused(path)
            return RemoteFile(input, entry.sizeBytes, entry.modifiedAt, start) { disconnect() }
        } catch (e: IOException) {
            throw ScraperException.Network(where, e)
        }
    }

    private fun listing(path: String): Array<FTPFile> {
        if (mlsd != false) {
            val files = client.mlistDir(path)
            if (FTPReply.isPositiveCompletion(client.replyCode)) {
                mlsd = true
                return files
            }
            // 500/501/502/504: no MLSD on this server. 550 and the like are real answers about the folder.
            if (client.replyCode !in UNSUPPORTED) throw refused(path)
            mlsd = false
        }
        val files = client.listFiles(path)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) throw refused(path)
        return files
    }

    private fun isFolder(path: String): Boolean = client.changeWorkingDirectory(path)

    private fun refused(path: String): ScraperException {
        val where = url.withPath(path).toString()
        val reply = client.replyString.trim()
        return when (client.replyCode) {
            530, 532 -> ScraperException.LoginFailed(url.host, reply)
            550 -> if (reply.contains("denied", true) || reply.contains("permission", true)) ScraperException.AccessDenied(where, 550, reply) else ScraperException.Http(where, 404)
            421 -> ScraperException.Busy(where)
            else -> ScraperException.AccessDenied(where, client.replyCode, reply)
        }
    }

    override fun close() = disconnect()

    private fun disconnect() {
        try {
            if (client.isConnected) {
                runCatching { client.logout() }
                client.disconnect()
            }
        } catch (_: IOException) {
        }
    }

    private fun join(folder: String, name: String) = if (folder.endsWith("/")) folder + name else "$folder/$name"

    private fun parseMdtm(value: String): java.time.Instant =
        java.time.LocalDateTime.parse(value.trim().take(14), java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(java.time.ZoneOffset.UTC)

    private val FTPFile.timestampInstant: java.time.Instant? get() = timestamp?.toInstant()

    companion object {
        private val UNSUPPORTED = setOf(500, 501, 502, 504)

        fun connect(config: FtpConfig, password: String?): FtpSession {
            val url = config.rootUrl
            val client = if (config.protocol == RemoteProtocol.Ftps) FTPSClient("TLS", false).apply {
                // The library's default only checks that a certificate has not expired; use the system's full validation.
                trustManager = TrustManagerUtils.getDefaultTrustManager(null)
                hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            } else FTPClient()
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.defaultTimeout = READ_TIMEOUT_MS
            client.controlEncoding = "UTF-8"
            client.setDataTimeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
            try {
                client.connect(config.host, config.effectivePort)
                if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                    throw ScraperException.Network(url.toString(), IOException("server said ${client.replyString.trim()}"))
                }
                client.soTimeout = READ_TIMEOUT_MS
                val secret = password ?: if (config.isAnonymous) "rshop@localhost" else ""
                if (!client.login(config.username, secret)) throw ScraperException.LoginFailed(config.host, client.replyString.trim())
                if (client is FTPSClient) {
                    client.execPBSZ(0)
                    client.execPROT("P")
                }
                client.enterLocalPassiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                client.setAutodetectUTF8(true)
                return FtpSession(client, url)
            } catch (e: ScraperException) {
                disconnectQuietly(client)
                throw e
            } catch (e: IOException) {
                disconnectQuietly(client)
                throw ScraperException.Network(url.toString(), e)
            }
        }

        private fun disconnectQuietly(client: FTPClient) {
            try {
                if (client.isConnected) client.disconnect()
            } catch (_: IOException) {
            }
        }

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
