package com.rshop.scraper.website

import com.rshop.scraper.GameSource
import com.rshop.scraper.ScraperException
import com.rshop.scraper.ScraperLog
import com.rshop.scraper.config.DetailRules
import com.rshop.scraper.config.FieldRule
import com.rshop.scraper.config.ListRules
import com.rshop.scraper.config.ScraperConfig
import com.rshop.scraper.config.SectionRules
import com.rshop.scraper.config.allOf
import com.rshop.scraper.config.firstOf
import com.rshop.scraper.http.Challenge
import com.rshop.scraper.http.FetchResult
import com.rshop.scraper.http.HtmlFetcher
import com.rshop.scraper.http.RemoteFile
import com.rshop.scraper.model.CatalogPage
import com.rshop.scraper.model.CatalogSection
import com.rshop.scraper.model.DownloadInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.rshop.scraper.model.ScrapedDownload
import com.rshop.scraper.model.ScrapedGame
import com.rshop.scraper.model.ScrapedGameDetails
import com.rshop.scraper.parse.ConsoleNames
import com.rshop.scraper.parse.Countdown
import com.rshop.scraper.parse.DateParser
import com.rshop.scraper.parse.Sha256Parser
import com.rshop.scraper.parse.CountParser
import com.rshop.scraper.parse.SizeParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Generic HTML source driven entirely by a [ScraperConfig]. */
class WebsiteSource(
    val config: ScraperConfig,
    private val fetcher: HtmlFetcher,
    private val log: ScraperLog = ScraperLog.None,
) : GameSource {

    override val id: String get() = config.id
    override val name: String get() = config.name

    private val base: HttpUrl = config.base
    private val interval = config.minRequestIntervalMs.milliseconds
    private val downloadPolicy = DownloadUrlPolicy(base, config.allowedDownloadHosts)

    private val linkPattern = config.list.linkPattern?.let(::Regex)

    /** Page URLs discovered through "next" links, for sites without a {page} template. */
    private val discoveredPages = ConcurrentHashMap<Int, HttpUrl>()

    /** Console pages read from the section index: never games, never index pages of a console. */
    private val sectionUrls: MutableSet<HttpUrl> = ConcurrentHashMap.newKeySet()

    override suspend fun getPage(page: Int): CatalogPage {
        if (page >= config.maxPages) return CatalogPage(emptyList(), hasNext = false)
        val url = pageUrl(page) ?: return CatalogPage(emptyList(), hasNext = false)
        val document = try {
            fetcher.fetch(url, interval)
        } catch (e: ScraperException.Http) {
            // A template page past the last one often simply does not exist: end of catalogue.
            if (e.code == 404 && page > 0) return CatalogPage(emptyList(), hasNext = false) else throw e
        }
        val games = parseList(document)
        if (games.isEmpty() && page == 0) {
            throw ScraperException.StructureChanged(url.toString(), "no game matched ${config.list.item}")
        }

        val next = config.list.nextPage.takeIf { it.isNotEmpty() }
            ?.let { document.firstOf(it)?.toHttpUrlOrNull() }
            ?.takeIf { it.host == base.host && it != url }
        if (next != null) discoveredPages[page + 1] = next

        val hasNext = games.isNotEmpty() && page + 1 < config.maxPages && when {
            config.usesPageTemplate -> config.list.nextPage.isEmpty() || next != null
            else -> next != null
        }
        return CatalogPage(games, hasNext)
    }

    /** Consoles listed on the section index, for sites organised by console. */
    suspend fun sections(): List<CatalogSection> {
        val rules = config.sections ?: return emptyList()
        val url = base.resolve(rules.url) ?: throw ScraperException.StructureChanged(rules.url, "invalid sections url")
        val sections = parseSections(fetcher.fetch(url, interval), rules, base).ifEmpty {
            throw ScraperException.StructureChanged(url.toString(), "no console matched ${rules.item}")
        }
        sections.mapNotNullTo(sectionUrls) { it.url.toHttpUrlOrNull() }
        return sections
    }

    /**
     * Sites organised by console are read in rounds: page 1 of every console, then page 2 of
     * every console, and so on. The catalogue fills evenly instead of one console at a time,
     * and an interrupted sync already has games for every console.
     */
    override fun crawl(): Flow<CatalogPage> {
        val rules = config.sections
        val useIndex = config.list.indexPages.isNotEmpty()
        if (rules == null && !useIndex) return super.crawl()
        return flow {
            val budget = RequestBudget(config.maxRequestsPerCrawl, used = 1)
            val cursors = if (rules == null) {
                listOfNotNull(pageUrl(0)?.let { ListingCursor(it, section = null, suffix = null, budget) })
            } else {
                // The first request of the budget is this console index.
                sections().map { ListingCursor(it.url.toHttpUrl(), it, rules.pageSuffix, budget) }
            }
            val active = ArrayDeque(cursors)
            while (active.isNotEmpty()) {
                val cursor = active.removeFirst()
                val page = cursor.next()
                if (budget.exhausted) {
                    log.warn("Crawl stopped after ${config.maxRequestsPerCrawl} requests (maxRequestsPerCrawl)")
                    return@flow
                }
                if (page == null) {
                    if (cursor.emitted == 0) log.warn("No game found for '${cursor.section?.name ?: "catalogue"}' at ${cursor.start}")
                    continue
                }
                emit(page)
                active.addLast(cursor)
            }
        }
    }

    private class RequestBudget(val max: Int, var used: Int) {
        val exhausted: Boolean get() = used > max
    }

    /**
     * Position in one listing (a console, or the whole catalogue): follows the next-page link
     * and, when [ListRules.indexPages] is set, every index link (A, B, C… or 1, 2, 3…) found on
     * the pages read, each page once.
     */
    private inner class ListingCursor(
        val start: HttpUrl,
        val section: CatalogSection?,
        private val suffix: String?,
        private val budget: RequestBudget,
    ) {
        private val useIndex = config.list.indexPages.isNotEmpty()
        private val queue = ArrayDeque(listOf(start))
        private val seenPages = hashSetOf(start)
        private val seenGames = HashSet<String>()
        private var page = 0
        private var pagesWithoutNewGames = 0
        var emitted = 0
            private set

        /** The next page that has games, or null when this listing is done. */
        suspend fun next(): CatalogPage? {
            while (queue.isNotEmpty() && page < config.maxPages) {
                val url = queue.removeFirst()
                if (++budget.used > budget.max) return null
                val document = try {
                    fetcher.fetch(url, interval)
                } catch (e: ScraperException.Http) {
                    // A template page past the end, or a dead index link, may simply not exist.
                    if (e.code == 404 && page > 0) {
                        if (useIndex) continue else return null
                    }
                    throw e
                }
                val games = parseList(document).map { it.copy(platform = it.platform ?: section?.name) }
                val next = if (useIndex) {
                    document.firstOf(config.list.nextPage.ifEmpty { ListRules.DEFAULT_NEXT_PAGE })?.toHttpUrlOrNull()
                } else {
                    nextSectionPage(document, url, start, suffix, page)
                }
                val links = buildList {
                    next?.let(::add)
                    if (useIndex) addAll(indexLinks(document))
                }
                links.map { it.newBuilder().fragment(null).build() }
                    .filter { it.host == base.host && it !in sectionUrls }
                    .forEach { if (seenPages.add(it)) queue.addLast(it) }
                page++
                if (games.isEmpty()) {
                    // A letter of the index may be empty; without an index, no games ends the listing.
                    if (useIndex) continue else return null
                }
                // Some sites serve their last page again for any page number: stop there.
                val fresh = games.count { seenGames.add(it.id) }
                pagesWithoutNewGames = if (fresh == 0) pagesWithoutNewGames + 1 else 0
                if (pagesWithoutNewGames >= MAX_PAGES_WITHOUT_NEW_GAMES) return null
                emitted++
                return CatalogPage(games, hasNext = queue.isNotEmpty() && page < config.maxPages, section = section?.name)
            }
            return null
        }
    }

    /** Links of the page's index (letters, page numbers): every [ListRules.indexPages] rule, merged. */
    private fun indexLinks(document: Document): List<HttpUrl> =
        config.list.indexPages.flatMap { FieldRule.parse(it).extractAll(document) }.mapNotNull { it.toHttpUrlOrNull() }

    private fun nextSectionPage(document: Document, current: HttpUrl, sectionUrl: HttpUrl, suffix: String?, page: Int): HttpUrl? {
        if (page + 1 >= config.maxPages) return null
        if (config.list.nextPage.isNotEmpty()) {
            return document.firstOf(config.list.nextPage)?.toHttpUrlOrNull()?.takeIf { it.host == base.host && it != current }
        }
        if (suffix != null) {
            return sectionUrl.resolve(suffix.replace(ScraperConfig.PAGE_PLACEHOLDER, (config.firstPageNumber + page + 1).toString()))
        }
        return null
    }

    override suspend fun getGameDetails(id: String): ScrapedGameDetails =
        when (val result = fetcher.open(detailsUrl(id), interval)) {
            is FetchResult.Page -> withFilesOfDownloadPage(getGameDetailsFrom(result.document, id))
            // Directory listings and plain file lists: the "game page" is the file itself.
            is FetchResult.File -> fileDetails(id, result.file)
        }

    /**
     * When the game page has a single "Download" button leading to a page that lists several
     * files (a table with one row per format or version), those files become the options.
     * Pages with a countdown are left as they are: their wait must happen right before the
     * download, which [resolveDownload] does.
     */
    private suspend fun withFilesOfDownloadPage(details: ScrapedGameDetails): ScrapedGameDetails {
        val only = details.downloads.singleOrNull()?.takeIf { it.viaPage } ?: return details
        if (config.downloadPageDelaySeconds > 0) return details
        val pageUrl = only.url.toHttpUrlOrNull() ?: return details
        val document = try {
            (fetcher.open(pageUrl, interval, useCache = false) as? FetchResult.Page)?.document
        } catch (e: ScraperException) {
            log.warn("Download page $pageUrl unreadable, kept as a single option", e)
            null
        } ?: return details
        if (Countdown.detect(document) > 0) return details

        val gameUrl = details.game.detailsUrl?.toHttpUrlOrNull()
        val files = config.details.downloads.asSequence()
            .flatMap { FieldRule.parse(it).extractAll(document) }
            .mapNotNull { it.toHttpUrlOrNull() }
            .filter { downloadPolicy.accepts(it) }
        val buttons = (config.details.downloadPage + DetailRules.DEFAULT_DOWNLOAD_BUTTONS).distinct().asSequence()
            .flatMap { FieldRule.parse(it).extractAll(document) }
            .mapNotNull { it.toHttpUrlOrNull() }
            .filter { it.host == base.host || downloadPolicy.accepts(it) }
        val links = (files + buttons)
            .map { it.newBuilder().fragment(null).build() }
            .filter { it != pageUrl && it != gameUrl && DownloadLinkFilter.accepts(document, it) }
            .distinct()
            .take(MAX_DOWNLOADS)
            .toList()
        if (links.size < 2) return details

        log.debug("Download page $pageUrl lists ${links.size} files")
        return details.copy(
            downloads = links.map { link ->
                val anchor = DownloadLinkFilter.anchors(document, link).firstOrNull()
                val fileName = link.pathSegments.lastOrNull { it.isNotEmpty() }?.takeIf { '.' in it }
                val row = anchor?.let(::rowOf)
                ScrapedDownload(
                    url = link.toString(),
                    fileName = fileName,
                    sizeBytes = row?.let { SIZE_IN_TEXT.find(it)?.value }?.let(SizeParser::parse),
                    viaPage = true,
                    label = DownloadLabel.of(row ?: anchor?.text(), fileName, details.game.title),
                )
            },
        )
    }

    /**
     * The table row (or list item) holding a file link, as "ZIP · v1.1 · 700 MB": the cells
     * describe the file; a cell that is only the download button is left out.
     */
    private fun rowOf(anchor: org.jsoup.nodes.Element): String? {
        val row = anchor.closest("tr") ?: return anchor.closest("li")?.text()?.takeIf { it.isNotBlank() }
        return row.select("td, th")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && !(BUTTON_ONLY.matches(it) && it == anchor.text().trim()) }
            .joinToString(" · ")
            .ifEmpty { null }
    }

    internal fun fileDetails(id: String, file: RemoteFile): ScrapedGameDetails {
        val name = file.fileName ?: id.substringAfterLast('/')
        return ScrapedGameDetails(
            game = ScrapedGame(
                id = id,
                title = DirectoryListing.titleOf(name),
                sizeBytes = file.sizeBytes,
                detailsUrl = file.url,
            ),
            downloads = listOf(ScrapedDownload(url = file.url, fileName = name, sizeBytes = file.sizeBytes)),
        )
    }

    /** Parses an already downloaded game page. */
    internal fun getGameDetailsFrom(document: Document, id: String): ScrapedGameDetails {
        val url = document.location()
        val rules = config.details
        val title = document.firstOf(rules.title)
            ?: throw ScraperException.StructureChanged(url, "no title on game page")

        val pageSize = SizeParser.parse(document.firstOf(rules.size))
        val pageSha = Sha256Parser.parse(document.firstOf(rules.sha256))
        val downloadUrls = document.allOf(rules.downloads)
            .mapNotNull { it.toHttpUrlOrNull() }
            .filter { candidate ->
                downloadPolicy.accepts(candidate).also { ok -> if (!ok) log.warn("Rejected download link $candidate") }
            }
            .filter { DownloadLinkFilter.accepts(document, it) }
            .distinct()
        // A page-level size or hash can only be attributed when there is a single file.
        val single = downloadUrls.size == 1
        val downloads = downloadUrls.take(MAX_DOWNLOADS).map { link ->
            val fileName = link.pathSegments.lastOrNull { it.isNotEmpty() }
            ScrapedDownload(
                url = link.toString(),
                fileName = fileName,
                sizeBytes = if (single) pageSize else null,
                sha256 = if (single) pageSha else null,
                label = DownloadLabel.of(linkText(document, link), fileName, title),
            )
        }.ifEmpty {
            // No direct file: fall back to the site's download pages (one per format when the
            // site offers several), resolved at download time.
            // Every rule is tried (not only the first that matches): a broad rule may only have
            // found menu links that the filter then rejects.
            val pages = (rules.downloadPage + DetailRules.DEFAULT_DOWNLOAD_BUTTONS).distinct().asSequence()
                .flatMap { FieldRule.parse(it).extractAll(document) }
                .mapNotNull { it.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build() }
                .filter { it.host == base.host && it.toString() != url && DownloadLinkFilter.accepts(document, it) }
                .distinct()
                .take(MAX_DOWNLOADS)
                .toList()
            pages.map { link ->
                ScrapedDownload(
                    url = link.toString(),
                    sizeBytes = if (pages.size == 1) pageSize else null,
                    sha256 = if (pages.size == 1) pageSha else null,
                    viaPage = true,
                    label = DownloadLabel.of(linkText(document, link), null, title),
                )
            }
        }

        return ScrapedGameDetails(
            game = ScrapedGame(
                id = id,
                title = title,
                coverUrl = document.firstOf(rules.cover)?.takeIfWebUrl(),
                platform = document.firstOf(rules.platform),
                genre = document.firstOf(rules.genre),
                version = document.firstOf(rules.version),
                sizeBytes = pageSize,
                detailsUrl = url,
                downloadCount = CountParser.parse(document.firstOf(rules.downloadCount)),
            ),
            description = document.firstOf(rules.description),
            screenshots = document.allOf(rules.screenshots).mapNotNull { it.takeIfWebUrl() }.distinct(),
            downloads = downloads,
            updatedAt = DateParser.parse(document.firstOf(rules.updatedAt)),
        )
    }

    override suspend fun resolveDownload(url: String, referer: String?): DownloadInfo {
        var current = url.toHttpUrlOrNull()?.takeIf { it.host == base.host || downloadPolicy.accepts(it) }
            ?: throw ScraperException.StructureChanged(url, "download link outside the allowed hosts")
        // Each hop is requested as if clicked from the previous page (Referer), as a browser does.
        var from: HttpUrl? = referer?.toHttpUrlOrNull()
        var sourcePage = referer ?: url
        val visited = HashSet<HttpUrl>()
        // Game page → download page → (confirmation page) → file, at most maxDownloadHops pages.
        for (hop in 0..config.maxDownloadHops) {
            visited += current
            // Never cached: download pages often carry short-lived tokens.
            val document = when (val result = fetcher.open(current, interval, useCache = false, referer = from)) {
                is FetchResult.File -> return DownloadInfo(
                    url = current.toString(),
                    fileName = result.file.fileName,
                    sizeBytes = result.file.sizeBytes,
                    contentType = result.file.contentType,
                    sourcePage = sourcePage,
                )
                is FetchResult.Page -> result.document
            }
            // The site asks visitors to wait before using the link: so do we (configured or announced).
            val waitSeconds = maxOf(config.downloadPageDelaySeconds, Countdown.detect(document))
            if (waitSeconds > 0) {
                log.debug("Waiting $waitSeconds s as asked by $current")
                delay(waitSeconds.seconds)
            }
            val next = nextDownloadLink(document, visited) ?: throw whyNoLink(document, current)
            log.debug("Download hop ${hop + 1}: $current -> $next")
            sourcePage = current.toString()
            from = current
            current = next
        }
        throw ScraperException.StructureChanged(url, "still no file after ${config.maxDownloadHops} download pages")
    }

    /** The most precise reason why [document] offers no link RShop can follow. */
    private fun whyNoLink(document: Document, page: HttpUrl): ScraperException {
        if (Challenge.hasCaptcha(document)) return ScraperException.Captcha(page.toString())
        val buttons = document.select("a, button, input[type=submit], input[type=button]").filter { element ->
            DOWNLOAD_TEXT.containsMatchIn(element.text().ifBlank { element.attr("value") })
        }
        // A download link to a host the source does not allow (another domain, a file host).
        buttons.asSequence()
            .filter { it.tagName() == "a" }
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull() }
            .firstOrNull { it.host != base.host && !downloadPolicy.accepts(it) }
            ?.let { foreign ->
                return ScraperException.StructureChanged(
                    page.toString(),
                    "the file is on ${foreign.host}, which is not allowed for this source (add it to allowedDownloadHosts)",
                )
            }
        // A button without a real link: JavaScript or a form decides where it goes.
        buttons.firstOrNull { element ->
            element.tagName() != "a" || element.attr("href").trim().let { it.isEmpty() || it == "#" || it.startsWith("javascript:", ignoreCase = true) }
        }?.let { element ->
            return ScraperException.StructureChanged(
                page.toString(),
                "the '${element.text().ifBlank { element.attr("value") }.take(40)}' button is driven by JavaScript or a form, which RShop does not run",
            )
        }
        return ScraperException.StructureChanged(page.toString(), "no file link in the download page (generated by script, login or protected)")
    }

    /** File-looking links first (allowed hosts), then same-site download buttons. */
    private fun nextDownloadLink(document: Document, visited: Set<HttpUrl>): HttpUrl? {
        val files = config.details.downloads.asSequence()
            .flatMap { FieldRule.parse(it).extractAll(document) }
            .mapNotNull { it.toHttpUrlOrNull() }
            .filter { downloadPolicy.accepts(it) && DownloadLinkFilter.accepts(document, it) }
        val pages = (config.details.downloadPage + DetailRules.DEFAULT_DOWNLOAD_BUTTONS).distinct().asSequence()
            .flatMap { FieldRule.parse(it).extractAll(document) }
            .mapNotNull { it.toHttpUrlOrNull() }
            .filter { (it.host == base.host || downloadPolicy.accepts(it)) && DownloadLinkFilter.accepts(document, it) }
        return (files + pages).firstOrNull { it.newBuilder().fragment(null).build() !in visited }
    }

    /** Text of the link pointing to [link] (button label, often naming the format). */
    private fun linkText(document: Document, link: HttpUrl): String? =
        document.select("a[href]").firstOrNull { it.absUrl("href").toHttpUrlOrNull() == link }
            ?.let { it.text().ifBlank { it.attr("title") } }
            ?.trim()?.takeIf { it.isNotEmpty() }

    override suspend fun search(query: String): List<ScrapedGame> {
        val template = config.searchUrl ?: return emptyList()
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8)
        var url: HttpUrl? = base.resolve(template.replace(ScraperConfig.QUERY_PLACEHOLDER, encoded)) ?: return emptyList()
        val results = LinkedHashMap<String, ScrapedGame>()
        val nextRules = config.list.nextPage.ifEmpty { ListRules.DEFAULT_NEXT_PAGE }
        var pages = 0
        while (url != null && pages < MAX_SEARCH_PAGES) {
            val document = fetcher.fetch(url, interval)
            val found = parseList(document).filter { results.putIfAbsent(it.id, it) == null }
            pages++
            if (found.isEmpty()) break
            url = document.firstOf(nextRules)?.toHttpUrlOrNull()?.takeIf { it.host == base.host && it != url }
        }
        return results.values.toList()
    }

    private companion object {
        const val MAX_SEARCH_PAGES = 5
        const val MAX_DOWNLOADS = 12
        const val MAX_PAGES_WITHOUT_NEW_GAMES = 2

        /** A size with an explicit unit ("700 MB", "1,2 Go"), so "v1.1" is never read as a size. */
        val SIZE_IN_TEXT = Regex("(?i)\\d+(?:[.,]\\d+)?\\s*(?:[kmgt]i?[bo]|bytes|octets)\\b")

        val DOWNLOAD_TEXT = Regex("(?i)^\\W*(download|télécharger)\\b")

        /** A cell holding only the button text ("Download", "Download ROM"). */
        val BUTTON_ONLY = Regex("(?i)^\\W*(download|télécharger)\\b.{0,20}$")
    }

    internal fun parseList(document: Document): List<ScrapedGame> {
        val rules = config.list
        val items = rules.item.asSequence().map { document.select(it) }.firstOrNull { it.isNotEmpty() } ?: return emptyList()
        val pattern = linkPattern
        return items.mapNotNull { item ->
            val link = item.firstOf(rules.link)?.toHttpUrlOrNull()
            if (link == null || link.host != base.host) return@mapNotNull null
            if (pattern != null && !pattern.matches(link.encodedPath + (link.encodedQuery?.let { "?$it" } ?: ""))) return@mapNotNull null
            if (link.newBuilder().fragment(null).build() in sectionUrls) return@mapNotNull null
            val title = item.firstOf(rules.title) ?: return@mapNotNull null
            // A card named after a console ("Nintendo GameCube", "PS2 (4 512)") leads to a console, not a game.
            if (ConsoleNames.isConsoleName(title)) {
                log.debug("Skipped console link '$title' ($link)")
                return@mapNotNull null
            }
            ScrapedGame(
                id = gameId(link),
                title = title,
                coverUrl = item.firstOf(rules.cover)?.takeIfWebUrl(),
                platform = item.firstOf(rules.platform),
                genre = item.firstOf(rules.genre),
                version = item.firstOf(rules.version),
                sizeBytes = SizeParser.parse(item.firstOf(rules.size)),
                detailsUrl = link.toString(),
                downloadCount = CountParser.parse(item.firstOf(rules.downloadCount)),
            )
        }.distinctBy { it.id }
    }

    private fun pageUrl(page: Int): HttpUrl? = when {
        page == 0 && config.firstPageUrl != null -> base.resolve(config.firstPageUrl)
        config.usesPageTemplate ->
            base.resolve(config.listUrl.replace(ScraperConfig.PAGE_PLACEHOLDER, (config.firstPageNumber + page).toString()))
        page == 0 -> base.resolve(config.listUrl)
        else -> discoveredPages[page]
    }

    /** Ids are the path (and query) of the game page, so they stay stable and never point off-site. */
    private fun gameId(link: HttpUrl): String = link.encodedPath + (link.encodedQuery?.let { "?$it" } ?: "")

    private fun detailsUrl(id: String): HttpUrl {
        require(id.startsWith("/")) { "Invalid game id '$id'" }
        val url = base.resolve(id)
        require(url != null && url.host == base.host) { "Game id '$id' does not belong to ${base.host}" }
        return url
    }

    private fun String.takeIfWebUrl(): String? =
        toHttpUrlOrNull()?.toString()
}

/** Consoles of a section index page: same-site links, filtered by [SectionRules.linkPattern]. */
internal fun parseSections(document: Document, rules: SectionRules, base: HttpUrl): List<CatalogSection> {
    val items = rules.item.asSequence().map { document.select(it) }.firstOrNull { it.isNotEmpty() } ?: return emptyList()
    val pattern = rules.linkPattern?.let(::Regex)
    val index = document.location().toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()
    return items.mapNotNull { item ->
        val link = item.firstOf(rules.link)?.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()
            ?.takeIf { it.host == base.host && it != index }
            ?.takeIf { url -> pattern == null || pattern.matches(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")) }
        val name = item.firstOf(rules.name)
        if (link == null || name == null) null else CatalogSection(name, link.toString())
    }.distinctBy { it.url }
}

/** Download links must be http(s) and on an allowed host (the site itself by default). */
class DownloadUrlPolicy(base: HttpUrl, allowedHosts: List<String>) {
    // By default the site and its own subdomains (dl.example.com, files.example.com).
    private val patterns = allowedHosts.map { it.lowercase().trim() }
        .ifEmpty { listOf(base.host, "*." + base.host.lowercase().removePrefix("www.")) }

    fun accepts(url: HttpUrl): Boolean {
        if (url.scheme != "https" && url.scheme != "http") return false
        val host = url.host.lowercase()
        return patterns.any { pattern ->
            if (pattern.startsWith("*.")) {
                val domain = pattern.removePrefix("*.")
                host == domain || host.endsWith(".$domain")
            } else {
                host == pattern
            }
        }
    }
}
