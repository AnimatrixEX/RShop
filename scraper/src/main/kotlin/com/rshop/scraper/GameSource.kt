package com.rshop.scraper

import com.rshop.scraper.model.CatalogPage
import com.rshop.scraper.model.DownloadInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.rshop.scraper.model.ScrapedGame
import com.rshop.scraper.model.ScrapedGameDetails

/**
 * A catalogue provider. Implementations (website, JSON API, local file) hide how the
 * data is obtained; the rest of the app only ever sees the scraped models.
 */
interface GameSource {
    /** Stable identifier of this source, used to namespace game ids. */
    val id: String

    /** Display name for settings and errors. */
    val name: String

    /** One catalogue page (0-based) plus whether another page follows. */
    suspend fun getPage(page: Int): CatalogPage

    /**
     * Every catalogue page in order, across sections when the source has some. Collection stops
     * at the first error, which propagates to the collector.
     */
    fun crawl(isKnown: (String) -> Boolean = NOTHING_KNOWN): Flow<CatalogPage> = flow {
        var page = 0
        while (true) {
            val result = getPage(page)
            // Incremental run: the first page is always read (it shows updates), then a page of
            // games that are all known means the rest was already seen.
            if (page > 0 && isKnown !== NOTHING_KNOWN && result.games.isNotEmpty() && result.games.all { isKnown(it.id) }) break
            emit(result)
            if (!result.hasNext) break
            page++
        }
    }

    /**
     * True when the last [crawl] stopped before the end of the catalogue (request budget spent):
     * the games it did not reach are still unknown, so it cannot count as a complete scan.
     */
    val crawlTruncated: Boolean get() = false

    /** Returns the games of the given catalogue page (0-based), or an empty list past the last page. */
    suspend fun getGames(page: Int): List<ScrapedGame> = getPage(page).games

    suspend fun getGameDetails(id: String): ScrapedGameDetails

    /**
     * Turns a download link (direct, redirecting, or a chain of download pages) into a URL that
     * serves the file, honoring any wait the site imposes. Never bypasses a CAPTCHA or login:
     * those end in a [ScraperException]. [referer] is the game page the link was found on.
     */
    suspend fun resolveDownload(url: String, referer: String? = null): DownloadInfo =
        DownloadInfo(url = url, fileName = null, sizeBytes = null, contentType = null, sourcePage = referer ?: url)

    /** Remote search; sources that cannot search return an empty list (the app also searches locally). */
    suspend fun search(query: String): List<ScrapedGame>

    companion object {
        /** Default for `crawl(isKnown)`: a full scan. Compared by identity. */
        val NOTHING_KNOWN: (String) -> Boolean = { false }
    }
}
