package com.rshop.scraper.config

import com.rshop.scraper.ScraperConfigException
import com.rshop.scraper.remote.RemoteProtocol
import com.rshop.scraper.remote.RemoteUrl
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * A games folder on an FTP, FTPS or SFTP server. Games are found the way they are on a Google
 * Drive: console folders hold game files or game folders. The password is never part of the
 * configuration (it is kept encrypted by the app and handed to the connector when needed).
 */
@Serializable
data class FtpConfig(
    override val id: String,
    override val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    /** 0 means the protocol's usual port (21 or 22). */
    val port: Int = 0,
    val username: String = ANONYMOUS,
    /** Folder to read, absolute on the server. */
    val rootPath: String = "/",
    /**
     * SFTP only: the server's key, `SHA256:` + base64, confirmed by the user the first time. Connections
     * to a server presenting another key are refused.
     */
    val hostKeyFingerprint: String? = null,
    /**
     * Console of every game, for a server that has no console folders (only game folders or files).
     * Null: the console is the name of the folder the game is in.
     */
    val platform: String? = null,
    /** Paths of the console folders to read; null reads every console found. */
    override val enabledSections: List<String>? = null,
    override val minRequestIntervalMs: Long = DEFAULT_INTERVAL_MS,
    /** Folder levels explored below [rootPath]. */
    val maxDepth: Int = DriveConfig.DEFAULT_DEPTH,
    /** Hard cap on folder listings for one crawl. */
    val maxRequestsPerCrawl: Int = 5_000,
    /** Tells a stored server source from the other kinds. */
    val type: String = TYPE,
) : SourceConfig {

    val effectivePort: Int get() = if (port > 0) port else protocol.defaultPort

    /** The root folder as an address. */
    val rootUrl: RemoteUrl get() = RemoteUrl(protocol, host.lowercase(), effectivePort, RemoteUrl.normalizePath(rootPath) ?: "/")

    override val location: String get() = rootUrl.toString()

    val isAnonymous: Boolean get() = username.equals(ANONYMOUS, ignoreCase = true)

    override fun withEnabledSections(sections: List<String>?): FtpConfig = copy(enabledSections = sections)

    override fun withInterval(intervalMs: Long): FtpConfig = copy(minRequestIntervalMs = intervalMs)

    override fun validate(): FtpConfig {
        val problems = mutableListOf<String>()
        if (!SourceConfig.ID_PATTERN.matches(id)) problems += "id must match ${SourceConfig.ID_PATTERN.pattern}"
        if (name.isBlank()) problems += "name is empty"
        if (!HOST.matches(host)) problems += "host is not a server name or address"
        if (port !in 0..65_535) problems += "port must be within 0..65535"
        if (username.isBlank()) problems += "username is empty"
        if (RemoteUrl.normalizePath(rootPath) == null) problems += "rootPath must not contain .."
        if (hostKeyFingerprint != null && !FINGERPRINT.matches(hostKeyFingerprint)) problems += "hostKeyFingerprint must look like SHA256:…"
        if (hostKeyFingerprint != null && protocol != RemoteProtocol.Sftp) problems += "hostKeyFingerprint only applies to sftp"
        if (platform != null && platform.isBlank()) problems += "platform is blank"
        if (enabledSections != null && enabledSections.isEmpty()) problems += "enabledSections is empty: choose at least one console"
        if (minRequestIntervalMs < MIN_INTERVAL_MS) problems += "minRequestIntervalMs must be at least $MIN_INTERVAL_MS"
        if (maxDepth !in 1..10) problems += "maxDepth must be within 1..10"
        if (maxRequestsPerCrawl < 1) problems += "maxRequestsPerCrawl must be at least 1"
        if (type != TYPE) problems += "type must be $TYPE"
        if (problems.isNotEmpty()) throw ScraperConfigException(problems)
        return this
    }

    override fun toJson(): String = ScraperConfig.JSON.encodeToString(serializer(), this)

    companion object {
        const val TYPE = "ftp"
        const val ANONYMOUS = "anonymous"
        const val MIN_INTERVAL_MS = 50L
        const val DEFAULT_INTERVAL_MS = 150L

        private val HOST = Regex("""[A-Za-z0-9]([A-Za-z0-9.\-]{0,251}[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+]""")
        private val FINGERPRINT = Regex("""SHA256:[A-Za-z0-9+/]{43}""")

        /** A source id stable for a server, user and folder: letters, digits and dashes only. */
        fun idFor(protocol: RemoteProtocol, host: String, port: Int, username: String, rootPath: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$username@${host.lowercase()}:$port$rootPath".toByteArray())
            val label = host.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24)
            return "${protocol.scheme}-$label-" + digest.take(3).joinToString("") { "%02x".format(it) }
        }

        fun fromJson(json: String): FtpConfig = ScraperConfig.JSON.decodeFromString(serializer(), json).validate()
    }
}
