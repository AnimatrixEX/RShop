package com.rshop.scraper.drive

import com.rshop.scraper.config.DriveConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A Google Drive folder as written in a shared link. */
data class DriveLink(val folderId: String, val resourceKey: String?) {

    companion object {
        private val HOSTS = setOf("drive.google.com", "www.drive.google.com")

        /**
         * Reads `…/drive/folders/ID`, `…/drive/u/0/folders/ID`, `…/open?id=ID` and
         * `…/drive/folders/ID?resourcekey=KEY`. Anything else (a single file, another site) is null.
         */
        fun parse(text: String): DriveLink? {
            val trimmed = text.trim()
            val url: HttpUrl = (trimmed.toHttpUrlOrNull() ?: "https://$trimmed".toHttpUrlOrNull()) ?: return null
            if (url.host.lowercase() !in HOSTS) return null
            val segments = url.pathSegments
            val fromPath = segments.indexOf("folders").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
            val fromQuery = url.queryParameter("id").takeIf { "file" !in segments }
            val id = (fromPath ?: fromQuery)?.takeIf { DriveConfig.FOLDER_ID.matches(it) } ?: return null
            val key = url.queryParameter("resourcekey")?.takeIf { it.isNotBlank() }
            return DriveLink(id, key)
        }
    }
}
