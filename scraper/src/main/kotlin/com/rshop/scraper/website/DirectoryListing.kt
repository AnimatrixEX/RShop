package com.rshop.scraper.website

import com.rshop.scraper.config.DetailRules
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

/**
 * Plain web-server directory listings ("Index of /roms/" from Apache, nginx, lighttpd, Caddy…):
 * sub-folders act as consoles and every other link is a file, without a game page.
 */
object DirectoryListing {
    private val INDEX_TITLE = Regex("(?i)^\\s*(index of|directory listing for|listing of)\\b")
    private val EXTENSION = Regex("(?i)\\.(tar\\.gz|tar\\.xz|tar\\.bz2|[a-z0-9]{1,4})$")

    /**
     * Regex on path+query matching game files: known archive/ROM extensions only, so readme.txt,
     * folders and sort links (`?C=N;O=D`) are never games.
     */
    val FILE_PATTERN: String = "(?i)^[^?]*\\.(" +DetailRules.DOWNLOAD_EXTENSIONS.joinToString("|") + ")$"

    /** List title rule: the link text without its extension. */
    const val TITLE_RULE = "@text ~^(.+?)(?:\\.(?:tar\\.gz|tar\\.xz|tar\\.bz2|[A-Za-z0-9]{1,4}))?$"

    fun isListing(document: Document): Boolean =
        INDEX_TITLE.containsMatchIn(document.title()) ||
            document.selectFirst("h1, h2")?.text()?.let { INDEX_TITLE.containsMatchIn(it) } == true

    /** Child folders of [page] linked from the listing (parent and sort links excluded). */
    fun subFolders(document: Document, page: HttpUrl): List<HttpUrl> = links(document, page)
        .filter { it.encodedPath.endsWith("/") && it.encodedQuery == null }

    fun files(document: Document, page: HttpUrl): List<HttpUrl> {
        val pattern = Regex(FILE_PATTERN)
        return links(document, page).filter { pattern.matches(it.encodedPath) && it.encodedQuery == null }
    }

    /** Same-host links strictly below [page]. */
    private fun links(document: Document, page: HttpUrl): List<HttpUrl> {
        val dir = page.encodedPath.substringBeforeLast('/') + "/"
        return document.select("a[href]")
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build() }
            .filter { it.host == page.host && it.encodedPath.startsWith(dir) && it.encodedPath.length > dir.length }
            .distinct()
    }

    /** "Super%20Game_(v1.1).zip" → "Super Game (v1.1)". */
    fun titleOf(fileName: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(fileName.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(fileName)
        return decoded.replace(EXTENSION, "").replace('_', ' ').trim().ifEmpty { decoded }
    }
}
