package com.rshop.data.backup

import com.rshop.scraper.ScraperConfigException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The backup file format: pretty JSON, tolerant of fields added by later versions. */
object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(backup: Backup): String = json.encodeToString(Backup.serializer(), backup)

    /** Throws [BackupException] for anything that is not a backup this version can restore. */
    fun decode(text: String): Backup {
        val backup = try {
            json.decodeFromString(Backup.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException("This file is not an RShop backup", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException("This file is not an RShop backup", e)
        }
        if (backup.format > Backup.FORMAT) throw BackupException("This backup comes from a newer version of RShop")
        try {
            backup.allSources.forEach { it.validate() }
        } catch (e: ScraperConfigException) {
            throw BackupException("A source of the backup is invalid: ${e.problems.joinToString("; ")}", e)
        }
        return backup
    }
}
