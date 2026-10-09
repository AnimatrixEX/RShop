package com.rshop.scraper.analysis

import com.rshop.scraper.parse.CountParser
import com.rshop.scraper.ScraperException
import com.rshop.scraper.ScraperLog
import com.rshop.scraper.config.DetailRules
import com.rshop.scraper.config.FieldRule
import com.rshop.scraper.config.ListRules
import com.rshop.scraper.config.ScraperConfig
import com.rshop.scraper.config.SectionRules
import com.rshop.scraper.config.firstOf
import com.rshop.scraper.http.Challenge
import com.rshop.scraper.http.FetchResult
import com.rshop.scraper.http.HtmlFetcher
import com.rshop.scraper.model.CatalogSection
import com.rshop.scraper.model.ScrapedGame
import com.rshop.scraper.parse.ConsoleNames
import com.rshop.scraper.parse.Countdown
import com.rshop.scraper.model.ScrapedGameDetails
import com.rshop.scraper.website.DirectoryListing
import com.rshop.scraper.website.WebsiteSource
import com.rshop.scraper.website.parseSections
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.milliseconds

enum class PaginationKind { Template, NextLink, Index, None }

data class SiteAnalysis(
    val config: ScraperConfig,
    val sampleGames: List<ScrapedGame>,
    val pagination: PaginationKind,
    /** First game's page parsed with the proposed config; null when it could not be read. */
    val sampleDetails: ScrapedGameDetails?,
    val detailsError: String?,
    /** Consoles found when the URL was a console index (sections mode). */
    val consoles: List<String> = emptyList(),
    /** The same consoles with their pages, so the user can choose which ones to read. */
    val sections: List<CatalogSection> = emptyList(),
    /** Set when files are behind a download page. */
    val downloadPage: DownloadPageInfo? = null,
)

data class DownloadPageInfo(
    val delaySeconds: Int,
    val directLinkFound: Boolean,
    /** The download page shows a CAPTCHA: files can only be fetched from a browser. */
    val captcha: Boolean = false,
)

/**
 * Proposes a [ScraperConfig] for a site from one listing page and one game page.
 * Heuristics only: the user reviews the preview, and the JSON stays editable for fine-tuning.
 * All requests go through [HtmlFetcher], so robots.txt and rate limits apply here too.
 */
class SiteAnalyzer(
    private val fetcher: HtmlFetcher,
    private val log: ScraperLog = ScraperLog.None,
) {

    suspend fun analyze(input: String): SiteAnalysis {
        val url = normalize(input) ?: throw IllegalArgumentException("Invalid URL: $input")
        val firstDocument = fetcher.fetch(url, INTERVAL)
        val firstUrl = firstDocument.location().toHttpUrlOrNull() ?: url
        if (DirectoryListing.isListing(firstDocument)) return analyzeDirectory(firstDocument, firstUrl)
        val firstItem = detectItemSelector(firstDocument, firstUrl)

        // A page whose "cards" are mostly console names is a console index: browse the consoles.
        firstItem?.let { detectSections(firstDocument, it) }?.let { rules ->
            return analyzeSections(ConsoleIndex(firstDocument, firstUrl, rules), firstDocument)
        }
        // Home page or page without cards: the consoles are usually behind the navigation menu.
        val isHome = firstUrl.encodedPath == "/"
        if (firstItem == null || isHome) {
            findConsoleIndex(firstDocument, firstUrl)?.let { return analyzeSections(it, firstDocument) }
            if (firstItem == null) {
                throw ScraperException.StructureChanged(firstUrl.toString(), "no repeated game cards nor console menu found on this page")
            }
        }

        val pagination = detectPagination(firstDocument, firstUrl) { doc ->
            doc.select(firstItem).mapNotNull { primaryLink(it, firstUrl)?.toString() }.toSet()
        }
        // Listing split by an index of links (A, B, C… or 1, 2, 3… without a URL template).
        val index = if (pagination.kind != PaginationKind.Template) detectIndexRule(firstDocument, firstUrl) else null
        // A single listing page (e.g. "latest games") while the menu leads to every console.
        if (pagination.kind == PaginationKind.None && index == null && !isHome) {
            findConsoleIndex(firstDocument, firstUrl)?.let { return analyzeSections(it, firstDocument) }
        }
        val config = baseConfig(firstDocument, firstDocument, firstUrl, firstItem).copy(
            listUrl = pagination.listUrl,
            firstPageUrl = if (pagination.kind == PaginationKind.Template) {
                firstUrl.encodedPath + (firstUrl.encodedQuery?.let { "?$it" } ?: "")
            } else {
                null
            },
            maxPages = if (pagination.kind != PaginationKind.None || index != null) 500 else 1,
        ).let { it.copy(list = it.list.copy(nextPage = listOfNotNull(pagination.nextRule), indexPages = indexRules(index))) }
        val kind = if (pagination.kind == PaginationKind.None && index != null) PaginationKind.Index else pagination.kind
        return finish(config, firstDocument, firstUrl, kind, consoles = emptyList(), defaultPlatform = null)
    }

    /**
     * Web-server directory listing: sub-folders become consoles (sections), other links are files.
     * There are no game pages, so each file is its own download.
     */
    private suspend fun analyzeDirectory(document: Document, pageUrl: HttpUrl): SiteAnalysis {
        val dir = pageUrl.encodedPath.substringBeforeLast('/') + "/"
        val folders = DirectoryListing.subFolders(document, pageUrl)
        val files = DirectoryListing.files(document, pageUrl)
        val base = pageUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val list = ListRules(
            item = listOf("a[href]"),
            link = listOf("@href"),
            title = listOf(DirectoryListing.TITLE_RULE, "@text"),
            cover = emptyList(),
            linkPattern = DirectoryListing.FILE_PATTERN,
        )
        var config = ScraperConfig(
            id = pageUrl.host.lowercase().removePrefix("www.").replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48),
            name = pageUrl.host.removePrefix("www.").take(60),
            baseUrl = base.toString(),
            listUrl = dir,
            maxPages = 1,
            list = list,
        )
        // Mostly folders: one section per folder (the files of each folder are its games).
        val useSections = folders.size >= 2 && folders.size >= files.size
        if (!useSections && files.isEmpty()) {
            throw ScraperException.StructureChanged(pageUrl.toString(), "directory listing without files or folders")
        }
        var sampleDocument = document
        var sampleUrl = pageUrl
        var consoles = emptyList<String>()
        var consoleSections = emptyList<CatalogSection>()
        if (useSections) {
            val rules = SectionRules(
                url = dir,
                item = listOf("a[href]"),
                link = listOf("@href"),
                name = listOf("@text ~^(.+?)/?$"),
                linkPattern = "^" + dir.replace(REGEX_META) { "\\" + it.value } + "[^/?]+/$",
            )
            config = config.copy(sections = rules, listUrl = dir)
            val sections = parseSections(document, rules, base)
            consoles = sections.map { it.name }
            consoleSections = sections
            // First folder that holds files gives the sample.
            for (section in sections.take(MAX_SECTION_TRIES)) {
                val doc = runCatching { fetcher.fetch(section.url.toHttpUrl(), INTERVAL) }.getOrNull() ?: continue
                val url = doc.location().toHttpUrlOrNull() ?: continue
                if (DirectoryListing.files(doc, url).isNotEmpty()) {
                    sampleDocument = doc
                    sampleUrl = url
                    break
                }
            }
        }
        val defaultPlatform = consoles.firstOrNull { sampleUrl.encodedPath.contains("/$it/", ignoreCase = true) }
        return finish(config, sampleDocument, sampleUrl, PaginationKind.None, consoles, defaultPlatform, consoleSections)
    }

    private data class ConsoleIndex(val document: Document, val url: HttpUrl, val rules: SectionRules)

    /** Sections mode: reads the consoles, then analyses the first console that lists games. */
    private suspend fun analyzeSections(index: ConsoleIndex, firstDocument: Document): SiteAnalysis {
        val base = index.url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val sections = parseSections(index.document, index.rules, base)
        if (sections.size < MIN_CARDS) throw ScraperException.StructureChanged(index.url.toString(), "console links could not be read")

        // Menus sometimes start with a brand or an empty console: try a few.
        var found: Triple<Document, HttpUrl, String>? = null
        for (section in sections.take(MAX_SECTION_TRIES)) {
            val sectionDocument = try {
                fetcher.fetch(section.url.toHttpUrl(), INTERVAL)
            } catch (e: ScraperException) {
                log.warn("Console page ${section.url} unreadable", e)
                continue
            }
            val sectionUrl = sectionDocument.location().toHttpUrlOrNull() ?: section.url.toHttpUrl()
            val item = detectItemSelector(sectionDocument, sectionUrl) ?: continue
            found = Triple(sectionDocument, sectionUrl, item)
            break
        }
        val (document, pageUrl, item) = found
            ?: throw ScraperException.StructureChanged(sections.first().url, "no game cards found on the first console pages")

        val pagination = detectPagination(document, pageUrl) { doc ->
            doc.select(item).mapNotNull { primaryLink(it, pageUrl)?.toString() }.toSet()
        }
        val rules = index.rules.copy(
            url = index.url.encodedPath + (index.url.encodedQuery?.let { "?$it" } ?: ""),
            pageSuffix = pagination.sectionSuffix,
        )
        val indexRule = if (pagination.kind != PaginationKind.Template) detectIndexRule(document, pageUrl) else null
        val config = baseConfig(firstDocument, document, pageUrl, item).copy(
            listUrl = rules.url,
            firstPageUrl = null,
            maxPages = if (pagination.kind != PaginationKind.None || indexRule != null) 500 else 1,
            sections = rules,
        ).let {
            it.copy(
                list = it.list.copy(
                    nextPage = if (rules.pageSuffix != null) emptyList() else listOfNotNull(pagination.nextRule),
                    indexPages = indexRules(indexRule),
                ),
            )
        }
        val sectionName = sections.firstOrNull { it.url.toHttpUrl().encodedPath == pageUrl.encodedPath }?.name
        val kind = if (pagination.kind == PaginationKind.None && indexRule != null) PaginationKind.Index else pagination.kind
        return finish(config, document, pageUrl, kind, sections.map { it.name }.distinct(), sectionName ?: sections.first().name, sections)
    }

    /**
     * Where the consoles are listed, from a page that is not itself a console index:
     * a navigation menu (dropdown) of console links, else a "Consoles" / "Systems" link
     * followed to see whether it leads to a console index.
     */
    private suspend fun findConsoleIndex(document: Document, pageUrl: HttpUrl): ConsoleIndex? {
        detectMenuSections(document, pageUrl)?.let { return ConsoleIndex(document, pageUrl, it) }
        val candidates = document.select("a[href]")
            .filter { CONSOLE_INDEX_TEXT.matches(linkName(it)) }
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build() }
            .filter { it.host == pageUrl.host && it != pageUrl }
            .distinct()
            .take(MAX_INDEX_TRIES)
        for (candidate in candidates) {
            val indexDocument = try {
                fetcher.fetch(candidate, INTERVAL)
            } catch (e: ScraperException) {
                continue
            }
            val indexUrl = indexDocument.location().toHttpUrlOrNull() ?: candidate
            val rules = detectItemSelector(indexDocument, indexUrl)?.let { detectSections(indexDocument, it) }
                ?: detectMenuSections(indexDocument, indexUrl)
            if (rules != null) return ConsoleIndex(indexDocument, indexUrl, rules)
        }
        return null
    }

    /**
     * Console links in menus (header, dropdowns, sidebars): same-site links named after consoles
     * and sharing a URL shape such as `/roms/<console>/`. Kept as a whole-page `a[href]` + path
     * pattern, scoped to the menu container when the pattern alone also matches other links.
     */
    private fun detectMenuSections(document: Document, pageUrl: HttpUrl): SectionRules? {
        data class Hit(val anchor: Element, val url: HttpUrl, val name: String)

        fun hitOf(anchor: Element): Hit? {
            val url = anchor.absUrl("href").toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build() ?: return null
            if (url.host != pageUrl.host || url.encodedPath == "/" || url == pageUrl) return null
            return Hit(anchor, url, linkName(anchor))
        }
        fun isConsole(hit: Hit) = looksLikeConsole(hit.name)
        fun prefixOf(url: HttpUrl): String {
            val segments = url.encodedPathSegments.filter { it.isNotEmpty() }
            return "/" + segments.dropLast(1).joinToString("") { "$it/" }
        }

        val hits = document.select("a[href]").mapNotNull(::hitOf)
        val consoleHits = hits.filter(::isConsole)
        if (consoleHits.size < MIN_CARDS) return null

        data class Candidate(val rules: SectionRules, val consoles: Int, val links: Int)
        val candidates = consoleHits.groupBy { prefixOf(it.url) }.flatMap { (prefix, group) ->
            if (group.map { it.url }.distinct().size < MIN_CARDS) return@flatMap emptyList()
            // Escaped by hand: Regex.escape's \Q…\E would make the JSON config hard to read.
            val pattern = "^" + prefix.replace(REGEX_META) { "\\" + it.value } + "[^/?#]+/?$"
            val regex = Regex(pattern)
            val scopes = listOf("") + group.mapNotNull { containerOf(it.anchor) }.distinct()
            scopes.mapNotNull { scope ->
                val item = if (scope.isEmpty()) "a[href]" else "$scope a[href]"
                val matched = document.select(item).mapNotNull(::hitOf)
                    .filter { regex.matches(it.url.encodedPath + (it.url.encodedQuery?.let { q -> "?$q" } ?: "")) }
                    .distinctBy { it.url }
                val consoles = matched.count(::isConsole)
                log.debug("Menu candidate '$item' $pattern: $consoles console names / ${matched.size} links")
                val ratio = if (scope.isEmpty()) MENU_CONSOLE_RATIO else SCOPED_MENU_CONSOLE_RATIO
                if (consoles < MIN_CARDS || consoles < matched.size * ratio) return@mapNotNull null
                Candidate(
                    SectionRules(url = "/", item = listOf(item), link = listOf("@href"), name = MENU_NAME_RULES, linkPattern = pattern),
                    consoles,
                    matched.size,
                )
            }
        }
        // Nearly all the consoles with the fewest other links; on a tie the whole-page rule
        // (listed first) survives layout changes best.
        val most = candidates.maxOfOrNull { it.consoles } ?: return null
        return candidates.filter { it.consoles >= most * 0.85 }.minBy { it.links - it.consoles }.rules
    }

    /**
     * Index of a listing: at least [MIN_CARDS] same-site links labelled with a single letter,
     * "#", "0-9" or a page number, in one container (`#az`, `div.letters`), as a rule.
     */
    private fun detectIndexRule(document: Document, pageUrl: HttpUrl): String? {
        val regex = Regex(INDEX_LABEL)
        val links = document.select("a[href]").filter { anchor ->
            regex.matches(anchor.ownText()) &&
                anchor.absUrl("href").toHttpUrlOrNull()?.let { it.host == pageUrl.host && it.newBuilder().fragment(null).build() != pageUrl } == true
        }
        val (scope, group) = links.groupBy { containerOf(it) }.maxByOrNull { it.value.size } ?: return null
        if (group.map { it.absUrl("href") }.distinct().size < MIN_CARDS) return null
        // Letters or numbers in the site's header menu are rarely an index of this listing.
        if (group.count { it.isInChrome() } > group.size / 2 && scope == null) return null
        val css = (scope?.let { "$it " } ?: "") + "a[href]:matchesOwn($INDEX_LABEL)"
        log.debug("Index links '$css': ${group.size}")
        return "$css @href"
    }

    /** The detected index plus numbered pagers, which pages of one letter often carry. */
    private fun indexRules(index: String?): List<String> =
        if (index == null) emptyList() else listOf(index, ListRules.NUMBERED_PAGER).distinct()

    /** Nearest ancestor that a stable selector can name: `#id` or `tag.class`. */
    private fun containerOf(anchor: Element): String? = anchor.parents().firstNotNullOfOrNull { parent ->
        if (parent.tagName() in setOf("body", "html")) return@firstNotNullOfOrNull null
        parent.id().takeIf { SIMPLE_CLASS.matches(it) }?.let { "#$it" }
            ?: parent.classNames().firstOrNull { SIMPLE_CLASS.matches(it) }?.let { "${parent.tagName()}.$it" }
    }

    /** A console label is short ("Mega Drive", "NES (120)"); a game title naming its console is not. */
    private fun looksLikeConsole(name: String): Boolean =
        name.length <= 32 && name.split(Regex("\\s+")).size <= 4 && ConsoleNames.CONTAINS.containsMatchIn(name)

    private fun linkName(anchor: Element): String =
        anchor.text().trim().ifEmpty { anchor.attr("title").trim() }.ifEmpty { anchor.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty() }

    /** Rules shared by listing and sections modes. */
    private fun baseConfig(firstDocument: Document, document: Document, pageUrl: HttpUrl, item: String): ScraperConfig {
        val cards = document.select(item)
        val listRules = ListRules(
            item = listOf(item),
            link = if (cards.first()?.tagName() == "a") listOf("@href", "a[href] @href") else listOf("a[href] @href", "@href"),
            title = bestRule(cards, TITLE_CANDIDATES, minRatio = 0.7)?.let { listOf(it) + ListRules(item = emptyList()).title }
                ?: ListRules(item = emptyList()).title,
            cover = bestCoverRule(cards)?.let { listOf(it) } ?: emptyList(),
            platform = bestRule(cards, labelRules(PLATFORM_LABELS) + listOf("[class*=platform]", "[class*=console]"), 0.5)?.let { listOf(it) } ?: emptyList(),
            genre = bestRule(cards, labelRules(GENRE_LABELS) + listOf("[class*=genre]", "[class*=category]"), 0.5)?.let { listOf(it) } ?: emptyList(),
            size = bestRule(cards, labelRules(SIZE_LABELS) + listOf("[class*=size]"), 0.5)?.let { listOf(it) } ?: emptyList(),
            downloadCount = bestRule(cards, COUNT_CANDIDATES, 0.5)?.let { listOf(it) } ?: emptyList(),
        )
        val firstUrl = firstDocument.location().toHttpUrlOrNull() ?: pageUrl
        val base = firstUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        return ScraperConfig(
            id = firstUrl.host.lowercase().removePrefix("www.").replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48),
            name = siteName(firstDocument, document, firstUrl),
            baseUrl = base.toString(),
            listUrl = "/",
            firstPageNumber = 1,
            searchUrl = detectSearch(firstDocument, firstUrl),
            list = listRules,
        )
    }

    /** Parses the sample games and refines the detail rules on the first game's page. */
    private suspend fun finish(
        initial: ScraperConfig,
        document: Document,
        pageUrl: HttpUrl,
        pagination: PaginationKind,
        consoles: List<String>,
        defaultPlatform: String?,
        sections: List<CatalogSection> = emptyList(),
    ): SiteAnalysis {
        var config = initial
        val games = WebsiteSource(config, fetcher, log).parseList(document).map { it.copy(platform = it.platform ?: defaultPlatform) }
        if (games.isEmpty()) throw ScraperException.StructureChanged(pageUrl.toString(), "cards found but no title/link could be read")

        // One extra request: refine the detail rules on the first game's page.
        var details: ScrapedGameDetails? = null
        var detailsError: String? = null
        var downloadPage: DownloadPageInfo? = null
        try {
            val detailUrl = games.first().detailsUrl!!.toHttpUrlOrNull()!!
            when (val opened = fetcher.open(detailUrl, INTERVAL)) {
                is FetchResult.Page -> {
                    config = config.copy(details = detectDetailRules(opened.document))
                    details = WebsiteSource(config, fetcher, log).getGameDetailsFrom(opened.document, games.first().id)
                }
                // Each entry is the file itself (plain file lists).
                is FetchResult.File -> details = WebsiteSource(config, fetcher, log).fileDetails(games.first().id, opened.file)
            }
            val pageLink = details.downloads.firstOrNull()?.takeIf { it.viaPage }?.url?.toHttpUrlOrNull()
            if (pageLink != null) {
                downloadPage = try {
                    val pageDoc = fetcher.fetch(pageLink, INTERVAL)
                    val delaySeconds = Countdown.detect(pageDoc)
                    config = config.copy(downloadPageDelaySeconds = delaySeconds)
                    DownloadPageInfo(
                        delaySeconds = delaySeconds,
                        directLinkFound = config.details.downloads.any { FieldRule.parse(it).extractAll(pageDoc).isNotEmpty() },
                        captcha = Challenge.hasCaptcha(pageDoc),
                    )
                } catch (e: ScraperException.NotHtml) {
                    // The button leads straight to the file (redirect): nothing to wait for.
                    DownloadPageInfo(delaySeconds = 0, directLinkFound = true)
                } catch (e: ScraperException.Captcha) {
                    DownloadPageInfo(delaySeconds = 0, directLinkFound = false, captcha = true)
                }
            }
        } catch (e: ScraperException) {
            log.warn("Game page analysis failed", e)
            detailsError = e.message
        }

        return SiteAnalysis(
            config = config.validate(),
            sampleGames = games.take(SAMPLE_SIZE),
            pagination = pagination,
            sampleDetails = details,
            detailsError = detailsError,
            consoles = consoles,
            sections = sections,
            downloadPage = downloadPage,
        )
    }

    /**
     * Site search from a GET form with a recognisable query field, as `/search?q={query}`.
     * POST forms are ignored: replaying them is not a plain page fetch.
     */
    private fun detectSearch(document: Document, pageUrl: HttpUrl): String? {
        val form = document.select("form").firstOrNull { form ->
            form.attr("method").ifEmpty { "get" }.equals("get", ignoreCase = true) &&
                form.selectFirst(SEARCH_INPUT) != null
        } ?: return null
        val field = form.selectFirst(SEARCH_INPUT)?.attr("name")?.takeIf { it.isNotBlank() } ?: return null
        val action = form.absUrl("action").toHttpUrlOrNull() ?: pageUrl
        if (action.host != pageUrl.host) return null
        val builder = action.newBuilder().query(null).fragment(null)
        form.select("input[type=hidden][name]").forEach { builder.addQueryParameter(it.attr("name"), it.attr("value")) }
        builder.addQueryParameter(field, "QUERY_TOKEN")
        val url = builder.build()
        return url.encodedPath + "?" + url.encodedQuery!!.replace("QUERY_TOKEN", ScraperConfig.QUERY_PLACEHOLDER)
    }

    /**
     * og:site_name when present, else the part of the page title shared by two pages of the site
     * ("Consoles - Homebrew Hub" + "NES - Homebrew Hub" → "Homebrew Hub"), else the host.
     */
    private fun siteName(first: Document, second: Document, url: HttpUrl): String {
        first.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(60) }
        val separators = Regex("\\s+[-|–—:·]\\s+")
        val a = first.title().split(separators).map { it.trim() }.filter { it.isNotEmpty() }
        val b = second.title().split(separators).map { it.trim() }.filter { it.isNotEmpty() }
        val shared = a.firstOrNull { it in b && (a.size > 1 || first !== second) }
        val fallback = if (a.size > 1) a.last() else a.firstOrNull()
        return (shared ?: fallback ?: url.host.removePrefix("www.")).take(60)
    }

    /** Section rules when most cards of [item] are named after consoles, else null. */
    private fun detectSections(document: Document, item: String): SectionRules? {
        val cards = document.select(item)
        val nameRule = bestRule(cards, TITLE_CANDIDATES + "@text", minRatio = 0.7) ?: return null
        val names = cards.mapNotNull { FieldRule.parse(nameRule).extractFirst(it) }
        val consoleLike = names.count(::looksLikeConsole)
        if (names.size < MIN_CARDS || consoleLike < names.size * 0.5) return null
        return SectionRules(
            url = "/",
            item = listOf(item),
            link = if (cards.first()?.tagName() == "a") listOf("@href", "a[href] @href") else listOf("a[href] @href", "@href"),
            name = listOf(nameRule, "@text"),
        )
    }

    // --- Listing -----------------------------------------------------------------------------

    private fun detectItemSelector(document: Document, pageUrl: HttpUrl): String? {
        val groups = HashMap<String, MutableList<Element>>()
        document.body().allElements.forEach { element ->
            if (element.tagName() in IGNORED_TAGS) return@forEach
            signatures(element).forEach { groups.getOrPut(it) { mutableListOf() }.add(element) }
        }

        return groups.mapNotNull { (selector, elements) ->
            if (elements.size < MIN_CARDS) return@mapNotNull null
            if (elements.count { it.isInChrome() } > elements.size / 2) return@mapNotNull null
            val links = elements.mapNotNull { primaryLink(it, pageUrl) }
            val distinct = links.distinct().size
            // Each card must lead to its own page.
            if (distinct < MIN_CARDS || distinct < elements.size * 0.8) return@mapNotNull null
            val withImage = elements.count { it.selectFirst("img") != null }.toDouble() / elements.size
            val withTitle = elements.count { it.firstOf(TITLE_CANDIDATES) != null }.toDouble() / elements.size
            // Real cards carry several pieces of text (title, platform, size…); a bare thumbnail link does not.
            val richness = elements.sumOf { card ->
                card.allElements.count { it.ownText().isNotBlank() }.coerceAtMost(4)
            }.toDouble() / (elements.size * 4)
            val naming = if (CARD_NAME.containsMatchIn(selector)) 1.1 else 1.0
            selector to distinct * (1.0 + withImage) * withTitle * (1.0 + richness) * naming
        }.maxByOrNull { it.second }?.first
    }

    /** `tag.class` for each simple class; `parent > tag` for class-less list items. */
    private fun signatures(element: Element): List<String> {
        val tag = element.tagName()
        val classes = element.classNames().filter { SIMPLE_CLASS.matches(it) }
        if (classes.isNotEmpty()) return classes.map { "$tag.$it" }
        if (tag in setOf("li", "article", "tr")) {
            val parent = element.parent() ?: return emptyList()
            val parentClass = parent.classNames().firstOrNull { SIMPLE_CLASS.matches(it) } ?: return emptyList()
            return listOf("${parent.tagName()}.$parentClass > $tag")
        }
        return emptyList()
    }

    private fun primaryLink(card: Element, pageUrl: HttpUrl): HttpUrl? {
        val anchors = if (card.tagName() == "a") listOf(card) else card.select("a[href]")
        return anchors.asSequence()
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull() }
            .firstOrNull { it.host == pageUrl.host && it.encodedPath != "/" && it.newBuilder().fragment(null).build() != pageUrl }
    }

    private fun bestCoverRule(cards: List<Element>): String? = COVER_CANDIDATES.firstOrNull { rule ->
        val values = cards.mapNotNull { FieldRule.parse(rule).extractFirst(it)?.toHttpUrlOrNull() }
        // A lazy-loading placeholder is the same image everywhere: not a cover.
        values.size >= cards.size / 2 && values.distinct().size > values.size / 2
    }

    private data class Pagination(
        val kind: PaginationKind,
        /** Site-relative first-page URL, with {page} when a template was found. */
        val listUrl: String,
        /** Rule yielding the next page URL, when pages are only reachable by link. */
        val nextRule: String?,
        /** Suffix to append to a console URL for page N (sections mode). */
        val sectionSuffix: String?,
    )

    /**
     * Finds how to reach page 2, in order: a "next" link, a numbered "2" link, then a few common
     * URL patterns tried for real (kept only if page 2 brings games page 1 did not have).
     */
    private suspend fun detectPagination(
        document: Document,
        pageUrl: HttpUrl,
        gamesOn: (Document) -> Set<String>,
    ): Pagination {
        val relative = pageUrl.encodedPath + (pageUrl.encodedQuery?.let { "?$it" } ?: "")
        fun sameSite(url: HttpUrl?) =
            url?.takeIf { it.host.removePrefix("www.") == pageUrl.host.removePrefix("www.") && it.newBuilder().fragment(null).build() != pageUrl }

        // Every match of a rule is looked at: a carousel arrow or a "#" link may come first.
        NEXT_CANDIDATES.firstNotNullOfOrNull { rule ->
            FieldRule.parse(rule).extractAll(document).firstNotNullOfOrNull { sameSite(it.toHttpUrlOrNull()) }?.let { rule to it }
        }?.let { (rule, next) ->
            return templateFrom(next, pageUrl) ?: Pagination(PaginationKind.NextLink, relative, rule, null)
        }

        // Numbered pagination without a "next" link: "1 2 3 … 40".
        document.select("a[href]")
            .firstOrNull { isPageTwoLink(it) && sameSite(it.absUrl("href").toHttpUrlOrNull()) != null }
            ?.let { link -> templateFrom(link.absUrl("href").toHttpUrl(), pageUrl)?.let { return it } }

        // No visible link (infinite scroll, JS buttons): try common URL patterns.
        val firstPage = gamesOn(document)
        for (candidate in probeCandidates(pageUrl)) {
            val doc = try {
                fetcher.fetch(candidate, INTERVAL)
            } catch (e: ScraperException) {
                continue
            }
            val games = gamesOn(doc)
            if (games.isNotEmpty() && (games - firstPage).isNotEmpty()) {
                templateFrom(candidate, pageUrl)?.let { return it }
            }
        }
        return Pagination(PaginationKind.None, relative, null, null)
    }

    /** The link to page 2 of a pager: "2" (also inside a span) or a label such as "Page 2". */
    private fun isPageTwoLink(anchor: Element): Boolean =
        anchor.text().trim() == "2" || PAGE_TWO_LABEL.matches(anchor.attr("aria-label")) || PAGE_TWO_LABEL.matches(anchor.attr("title"))

    private fun probeCandidates(pageUrl: HttpUrl): List<HttpUrl> {
        val dir = pageUrl.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
        return listOfNotNull(
            pageUrl.newBuilder().setQueryParameter("page", "2").build(),
            pageUrl.newBuilder().encodedPath(dir + "page/2/").build(),
            pageUrl.newBuilder().setQueryParameter("p", "2").build(),
            pageUrl.newBuilder().setQueryParameter("paged", "2").build(),
            pageUrl.newBuilder().setQueryParameter("pg", "2").build(),
            pageUrl.newBuilder().encodedPath(dir + "2/").build(),
        )
    }

    /** Template from a page-2 URL: the "2" in a query value or in a path segment becomes {page}. */
    private fun templateFrom(next: HttpUrl, pageUrl: HttpUrl): Pagination? {
        val page = ScraperConfig.PAGE_PLACEHOLDER
        next.queryParameterNames.firstOrNull { next.queryParameter(it) == "2" && pageUrl.queryParameter(it) != "2" }?.let { param ->
            val template = next.newBuilder().setQueryParameter(param, "PAGE_TOKEN").build()
            val text = template.encodedPath + "?" + template.encodedQuery!!.replace("PAGE_TOKEN", page)
            val suffix = if (next.encodedPath == pageUrl.encodedPath) "?$param=$page" else null
            return Pagination(PaginationKind.Template, text, null, suffix)
        }
        val segments = next.encodedPathSegments
        val index = segments.indexOfLast { it == "2" }
        if (index < 0) return null
        val path = "/" + segments.mapIndexed { i, segment -> if (i == index) page else segment }.joinToString("/")
        val sectionPath = pageUrl.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
        val suffix = if (path.startsWith(sectionPath)) path.removePrefix(sectionPath) else null
        return Pagination(PaginationKind.Template, path + (next.encodedQuery?.let { "?$it" } ?: ""), null, suffix)
    }

    // --- Game page ---------------------------------------------------------------------------

    private fun detectDetailRules(document: Document): DetailRules {
        val defaults = DetailRules()
        fun pick(candidates: List<String>, fallback: List<String>): List<String> =
            candidates.firstOrNull { FieldRule.parse(it).extractFirst(document) != null }
                ?.let { listOf(it) + fallback.filter { f -> f != it } } ?: fallback

        val downloadsFound = defaults.downloads.any { FieldRule.parse(it).extractAll(document).isNotEmpty() }
        // Kept even when this game has a direct link: other games of the site may not.
        val detectedPage = TEXT_DOWNLOAD_CANDIDATES.filter { FieldRule.parse(it).extractFirst(document) != null }
        val downloadPage = (detectedPage + TEXT_DOWNLOAD_CANDIDATES).distinct().takeIf { !downloadsFound || detectedPage.isNotEmpty() }
            ?: TEXT_DOWNLOAD_CANDIDATES
        return defaults.copy(
            downloadPage = downloadPage,
            description = pick(DESCRIPTION_CANDIDATES, defaults.description),
            cover = pick(listOf("meta[property=og:image] @content") + COVER_CANDIDATES.map { "[class*=cover] $it" }, defaults.cover),
            screenshots = pick(SCREENSHOT_CANDIDATES, emptyList()),
            downloads = defaults.downloads,
            size = pick(labelRules(SIZE_LABELS), emptyList()),
            version = pick(labelRules(VERSION_LABELS), emptyList()),
            platform = pick(listOf("[itemprop=gamePlatform]") + labelRules(PLATFORM_LABELS), emptyList()),
            genre = pick(listOf("[itemprop=genre]") + labelRules(GENRE_LABELS), defaults.genre),
        )
    }

    private fun bestRule(cards: List<Element>, candidates: List<String>, minRatio: Double): String? =
        candidates.firstOrNull { rule ->
            cards.count { FieldRule.parse(rule).extractFirst(it) != null } >= cards.size * minRatio
        }

    private fun Element.isInChrome(): Boolean = parents().any { it.tagName() in setOf("nav", "header", "footer", "aside") }

    private fun normalize(input: String): HttpUrl? {
        val trimmed = input.trim()
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()
    }

    companion object {
        private val INTERVAL = 1_500.milliseconds
        private const val MIN_CARDS = 3
        /** "A", "#", "0-9", "12": the label of an index or page link. */
        private const val INDEX_LABEL = "^\\s*([A-Za-zÀ-Ýà-ý]|#|0-9|\\d\\d?\\d?\\d?)\\s*$"
        private const val SAMPLE_SIZE = 12
        private const val MAX_SECTION_TRIES = 3
        private const val MAX_INDEX_TRIES = 2
        private val REGEX_META = Regex("[\\\\^$.|?*+()\\[\\]{}]")
        /** Share of console-named links required for a menu to count as the console list. */
        private const val MENU_CONSOLE_RATIO = 0.6
        /** Inside one menu container, unknown platforms (GP32, Dingoo…) are tolerated. */
        private const val SCOPED_MENU_CONSOLE_RATIO = 0.3
        private val CONSOLE_INDEX_TEXT = Regex(
            "(?i)\\s*(all\\s+|toutes?\\s+les\\s+|tous\\s+les\\s+)?(consoles?|systems?|systèmes?|platforms?|plateformes?|roms|emulators?|émulateurs?)\\s*",
        )
        /** Menu labels often carry a count or a suffix: "NES (1 234)", "SNES ROMs". */
        private val MENU_NAME_RULES = listOf(
            "@text ~(?i)^(.+?)(?:\\s+roms?)?(?:\\s*\\(\\s*\\d[\\d\\s,.]*\\))?$",
            "@title",
            "img[alt] @alt",
        )
        private val SIMPLE_CLASS = Regex("[A-Za-z][A-Za-z0-9_-]*")
        private const val SEARCH_INPUT =
            "input[type=search][name], input[name=q], input[name=s], input[name=search], input[name=query], input[name=keyword], input[name=keywords]"
        private val CARD_NAME = Regex("(?i)game|card|item|product|post|entry|rom|tile")
        private val IGNORED_TAGS = setOf("html", "body", "main", "nav", "header", "footer", "aside", "form", "select", "option", "script", "style", "ul", "ol", "table", "tbody", "thead")

        private val TITLE_CANDIDATES = listOf("h1, h2, h3, h4, h5", "[class*=title]", "[class*=name]", "a[title] @title", "img[alt] @alt", "a")
        private val COVER_CANDIDATES = listOf("img @data-src", "img @data-lazy-src", "img @data-original", "img @src")
        private val NEXT_CANDIDATES = ListRules.DEFAULT_NEXT_PAGE
        private val PAGE_TWO_LABEL = Regex("(?i)\\s*(go to )?(page|p\u00e1gina|pagina|seite)\\s*2\\s*")
        private val DESCRIPTION_CANDIDATES = listOf(
            "[itemprop=description]", "#description", ".description", "[class*=description]",
            "[class*=synopsis]", "[class*=summary]", "article p",
        )
        private val SCREENSHOT_CANDIDATES = listOf("screenshot", "gallery", "carousel", "slider").flatMap { key ->
            listOf("[class*=$key] img @data-src", "[class*=$key] img @src", "[class*=$key] a[href$=.jpg] @href", "[class*=$key] a[href$=.png] @href")
        }
        private val TEXT_DOWNLOAD_CANDIDATES = DetailRules.DEFAULT_DOWNLOAD_BUTTONS
        private val PLATFORM_LABELS = listOf("platform", "plateforme", "console", "system", "système")
        private val GENRE_LABELS = listOf("genre", "genres", "category", "catégorie")
        private val SIZE_LABELS = listOf("size", "file size", "taille", "poids")

        /** A download counter in a card's text: "12 345 downloads", "Downloads: 1.2k". */
        private val COUNT_CANDIDATES = listOf(
            "~(?i)${CountParser.NUMBER}\\s+${DetailRules.COUNT_WORDS}\\b",
            "~(?i)${DetailRules.COUNT_WORDS}\\s*[:：]\\s*${CountParser.NUMBER}",
        )
        private val VERSION_LABELS = listOf("version", "revision", "révision")

        /**
         * Two common layouts for "Label: value": the value in the next element
         * (`<dt>Size</dt><dd>2 MB</dd>`) or in the same element (`<li>Size: 2 MB</li>`).
         */
        fun labelRules(labels: List<String>): List<String> {
            val alternatives = labels.joinToString("|") { Regex.escape(it).replace(" ", "\\s") }
            return listOf(
                ":matchesOwn((?i)^\\s*($alternatives)\\s*:?\\s*$) + *",
                ":matchesOwn((?i)^\\s*($alternatives)\\s*:\\s*\\S) @ownText ~:\\s*(.+)$",
            )
        }
    }
}
