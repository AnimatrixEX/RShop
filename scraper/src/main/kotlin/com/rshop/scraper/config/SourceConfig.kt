package com.rshop.scraper.config

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What every catalogue source has in common, whatever it reads: a website ([ScraperConfig]) or a
 * Google Drive folder ([DriveConfig]). The rest of the app only needs this much; anything more
 * specific is asked with a type check.
 */
interface SourceConfig {
    /** Namespace for game ids: lower-case letters, digits and dashes. */
    val id: String
    val name: String

    /** Consoles to read (as [com.rshop.scraper.model.CatalogSection.url]); null reads every console. */
    val enabledSections: List<String>?

    /** Minimum delay between two requests to the server. */
    val minRequestIntervalMs: Long

    /** Where the catalogue comes from, to show next to the name. */
    val location: String

    fun withEnabledSections(sections: List<String>?): SourceConfig
    fun withInterval(intervalMs: Long): SourceConfig

    /** Throws [com.rshop.scraper.ScraperConfigException] listing every problem found. */
    fun validate(): SourceConfig
    fun toJson(): String

    companion object {
        val ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,47}")

        /** Reads a stored source; files written before Drive existed have no type and are websites. */
        fun fromJson(json: String): SourceConfig {
            val type = ScraperConfig.JSON.parseToJsonElement(json).jsonObject["type"]?.jsonPrimitive?.content
            return if (type == DriveConfig.TYPE) DriveConfig.fromJson(json) else ScraperConfig.fromJson(json)
        }
    }
}
