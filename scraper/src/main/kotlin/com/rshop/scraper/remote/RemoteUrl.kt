package com.rshop.scraper.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/** The file transfer protocols a server source can speak. */
@Serializable
enum class RemoteProtocol(val scheme: String, val defaultPort: Int) {
    /** Plain FTP: nothing is encrypted, password included. */
    @SerialName("ftp") Ftp("ftp", 21),

    /** FTP over explicit TLS (AUTH TLS). */
    @SerialName("ftps") Ftps("ftps", 21),

    /** SSH file transfer. */
    @SerialName("sftp") Sftp("sftp", 22);

    /** Whether the password and the files travel encrypted. */
    val encrypted: Boolean get() = this != Ftp

    companion object {
        fun ofScheme(scheme: String?): RemoteProtocol? = entries.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }
    }
}

/**
 * Address of a file or folder on an FTP/FTPS/SFTP server: `sftp://host:2222/games/GBA/Zelda.zip`.
 * It never holds a user name or password (those are kept apart, encrypted), so it can be stored,
 * logged and shown freely.
 */
data class RemoteUrl(
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    /** Absolute path on the server, decoded, always starting with `/`. */
    val path: String,
) {
    val fileName: String get() = path.trimEnd('/').substringAfterLast('/')

    override fun toString(): String {
        val portPart = if (port == protocol.defaultPort) "" else ":$port"
        val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        return "${protocol.scheme}://$host$portPart$encoded"
    }

    fun withPath(newPath: String) = copy(path = newPath)

    companion object {
        /** Null when [text] is not an ftp://, ftps:// or sftp:// address, or when it carries credentials. */
        fun parse(text: String): RemoteUrl? {
            val uri = try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            val protocol = RemoteProtocol.ofScheme(uri.scheme) ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            if (uri.rawUserInfo != null) return null
            val path = normalizePath(uri.path.orEmpty()) ?: return null
            return RemoteUrl(protocol, host.lowercase(), if (uri.port > 0) uri.port else protocol.defaultPort, path)
        }

        /**
         * A clean absolute path: one leading `/`, no empty, `.` or `..` segments. Null when the path tries to
         * climb out of its folder.
         */
        fun normalizePath(path: String): String? {
            val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.any { it == ".." || '\u0000' in it }) return null
            return "/" + segments.joinToString("/")
        }
    }
}
