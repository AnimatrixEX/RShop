package com.rshop.scraper.http

import org.jsoup.nodes.Document
import java.net.URLDecoder
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** What a URL turned out to be: a web page, or a file whose headers only were read. */
sealed interface FetchResult {
    class Page(val document: Document) : FetchResult
    class File(val file: RemoteFile) : FetchResult
}

/** Headers of a file response. [url] is what was requested, [finalUrl] where redirects ended. */
data class RemoteFile(
    val url: String,
    val finalUrl: String,
    val fileName: String?,
    val sizeBytes: Long?,
    val contentType: String?,
)

/** `Content-Disposition: attachment; filename="game.zip"; filename*=UTF-8''game%20x.zip` (RFC 6266). */
internal data class ContentDisposition(val attachment: Boolean, val fileName: String?) {
    companion object {
        private val EXTENDED = Regex("(?i)filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)")
        private val PLAIN = Regex("(?i)(?<![*\\w])filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)")

        fun parse(header: String?): ContentDisposition {
            if (header.isNullOrBlank()) return ContentDisposition(false, null)
            val attachment = header.trimStart().startsWith("attachment", ignoreCase = true)
            val extended = EXTENDED.find(header)?.let { match ->
                val charset = match.groupValues[1].ifBlank { "UTF-8" }
                runCatching { URLDecoder.decode(match.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
            }
            val plain = PLAIN.find(header)?.let { it.groupValues[2].ifEmpty { it.groupValues[1] }.trim() }
            // Only the last path segment: a server-provided name never chooses a directory.
            val name = (extended ?: plain)?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            return ContentDisposition(attachment, name)
        }
    }
}

/** Small LRU of recently fetched pages, kept as HTML text (documents are mutable) for [ttl]. */
internal class PageCache(
    private val timeSource: TimeSource.WithComparableMarks,
    private val ttl: Duration,
    private val maxEntries: Int = 48,
    private val maxPageChars: Int = 1_000_000,
) {
    private class Entry(val html: String, val baseUri: String, val expires: ComparableTimeMark)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > maxEntries
    }

    fun get(url: String): Document? = synchronized(entries) {
        val entry = entries[url] ?: return null
        if (entry.expires.hasPassedNow()) {
            entries.remove(url)
            return null
        }
        org.jsoup.Jsoup.parse(entry.html, entry.baseUri)
    }

    fun put(url: String, document: Document) {
        if (ttl <= Duration.ZERO) return
        val html = document.outerHtml()
        if (html.length > maxPageChars) return
        synchronized(entries) { entries[url] = Entry(html, document.location(), timeSource.markNow() + ttl) }
    }
}
