package com.rshop.scraper.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import com.rshop.scraper.ScraperException
import com.rshop.scraper.config.FtpConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** SFTP through JSch. The server's key must match the one the user trusted; it is never accepted blindly. */
internal class SftpSession private constructor(
    private val session: Session,
    private val channel: ChannelSftp,
    private val url: RemoteUrl,
) : RemoteSession {

    override fun list(path: String): List<RemoteEntry> = try {
        @Suppress("UNCHECKED_CAST")
        val entries = channel.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
        entries.filter { it.filename != "." && it.filename != ".." }.mapNotNull { entry ->
            val full = if (path.endsWith("/")) path + entry.filename else path + "/" + entry.filename
            val attrs = entry.attrs
            val directory = when {
                attrs.isDir -> true
                attrs.isLink -> runCatching { channel.stat(full).isDir }.getOrNull() ?: return@mapNotNull null
                attrs.isReg -> false
                else -> return@mapNotNull null
            }
            RemoteEntry(entry.filename, full, directory, if (directory) null else attrs.size.takeIf { it >= 0 }, attrs.mTime.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it.toLong()) })
        }
    } catch (e: SftpException) {
        throw failure(path, e)
    }

    override fun stat(path: String): RemoteEntry? = try {
        val attrs = channel.stat(path)
        RemoteEntry(
            path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, path, attrs.isDir,
            if (attrs.isDir) null else attrs.size.takeIf { it >= 0 },
            attrs.mTime.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it.toLong()) },
        )
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw failure(path, e)
    }

    override fun open(path: String, offset: Long): RemoteFile {
        val entry = stat(path)
        if (entry == null || entry.isDirectory) throw ScraperException.Http(url.withPath(path).toString(), 404)
        return try {
            val input = channel.get(path, null as SftpProgressMonitor?, offset)
            RemoteFile(input, entry.sizeBytes, entry.modifiedAt, offset) { close() }
        } catch (e: SftpException) {
            throw failure(path, e)
        }
    }

    override fun close() {
        runCatching { if (channel.isConnected) channel.disconnect() }
        runCatching { if (session.isConnected) session.disconnect() }
    }

    private fun failure(path: String, e: SftpException): ScraperException {
        val where = url.withPath(path).toString()
        return when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> ScraperException.Http(where, 404)
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> ScraperException.AccessDenied(where, 403, e.message)
            else -> ScraperException.Network(where, IOException(e.message, e))
        }
    }

    /** Remembers the key a server presented and accepts it only when it is the pinned one. */
    private class PinnedHostKey(private val expected: String?) : HostKeyRepository {
        @Volatile var seen: String? = null
        @Volatile var rejected = false

        override fun check(host: String?, key: ByteArray): Int {
            val fingerprint = fingerprint(key)
            seen = fingerprint
            if (fingerprint == expected) return HostKeyRepository.OK
            rejected = true
            return if (expected == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "RShop"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        /** `SHA256:` + base64 without padding, the form OpenSSH prints. */
        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

        fun connect(config: FtpConfig, password: String?): SftpSession {
            val url = config.rootUrl
            val pinned = PinnedHostKey(config.hostKeyFingerprint)
            val session = JSch().getSession(config.username, config.host, config.effectivePort)
            try {
                session.setHostKeyRepository(pinned)
                // The repository above is the only authority: with "yes", anything it does not accept fails the connection.
                session.setConfig("StrictHostKeyChecking", "yes")
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                session.setConfig("MaxAuthTries", "2")
                password?.let(session::setPassword)
                session.setTimeout(READ_TIMEOUT_MS)
                session.setServerAliveInterval(15_000)
                session.connect(CONNECT_TIMEOUT_MS)
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect(CONNECT_TIMEOUT_MS)
                runCatching { channel.setFilenameEncoding("UTF-8") }
                return SftpSession(session, channel, url)
            } catch (e: JSchException) {
                runCatching { session.disconnect() }
                val seen = pinned.seen
                when {
                    pinned.rejected && seen != null && config.hostKeyFingerprint == null -> throw ScraperException.HostKeyUnknown(config.host, seen)
                    pinned.rejected && seen != null -> throw ScraperException.HostKeyChanged(config.host, config.hostKeyFingerprint.orEmpty(), seen)
                }
                val message = e.message.orEmpty()
                when {
                    message.contains("Auth fail", true) || message.contains("Auth cancel", true) -> throw ScraperException.LoginFailed(config.host, message)
                    e.cause is UnknownHostException || e.cause is SocketTimeoutException || e.cause is IOException ->
                        throw ScraperException.Network(url.toString(), e.cause as IOException)
                    else -> throw ScraperException.Network(url.toString(), IOException(message, e))
                }
            }
        }

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
