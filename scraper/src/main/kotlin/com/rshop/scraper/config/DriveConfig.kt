package com.rshop.scraper.config

import com.rshop.scraper.ScraperConfigException
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** A Google Drive folder shared with "anyone with the link", read through the Drive API. */
@Serializable
data class DriveConfig(
    override val id: String,
    override val name: String,
    val folderId: String,
    /** Resource key of a link-shared folder created before September 2021; null for most folders. */
    val resourceKey: String? = null,
    /**
     * Console of every game, for a Drive that has no console folders (only game folders or files).
     * Null: the console is the name of the folder the game is in.
     */
    val platform: String? = null,
    /** Folder ids of the consoles to read; null reads every console found. */
    override val enabledSections: List<String>? = null,
    override val minRequestIntervalMs: Long = DEFAULT_INTERVAL_MS,
    /** Folder levels explored below the shared folder. */
    val maxDepth: Int = DEFAULT_DEPTH,
    /** Hard cap on API requests for one crawl, so a huge or looping tree can never run away. */
    val maxRequestsPerCrawl: Int = 5_000,
    /** Tells a stored Drive source from a website one. */
    val type: String = TYPE,
) : SourceConfig {

    override val location: String get() = "https://drive.google.com/drive/folders/$folderId"

    override fun withEnabledSections(sections: List<String>?): DriveConfig = copy(enabledSections = sections)

    override fun withInterval(intervalMs: Long): DriveConfig = copy(minRequestIntervalMs = intervalMs)

    override fun validate(): DriveConfig {
        val problems = mutableListOf<String>()
        if (!SourceConfig.ID_PATTERN.matches(id)) problems += "id must match ${SourceConfig.ID_PATTERN.pattern}"
        if (name.isBlank()) problems += "name is empty"
        if (!FOLDER_ID.matches(folderId)) problems += "folderId is not a Drive folder id"
        if (resourceKey != null && !RESOURCE_KEY.matches(resourceKey)) problems += "resourceKey is malformed"
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
        const val TYPE = "drive"
        const val MIN_INTERVAL_MS = 100L
        const val DEFAULT_INTERVAL_MS = 400L
        const val DEFAULT_DEPTH = 6

        /** Drive ids are URL-safe base64-ish strings of 20 characters or more. */
        val FOLDER_ID = Regex("[A-Za-z0-9_-]{10,100}")
        private val RESOURCE_KEY = Regex("[A-Za-z0-9_-]{1,100}")

        /** A source id stable for a folder: Drive ids hold capitals, source ids may not. */
        fun idFor(folderId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(folderId.toByteArray())
            return "gdrive-" + digest.take(5).joinToString("") { "%02x".format(it) }
        }

        fun fromJson(json: String): DriveConfig = ScraperConfig.JSON.decodeFromString(serializer(), json).validate()
    }
}
