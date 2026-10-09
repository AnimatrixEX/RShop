package com.rshop.scraper.config

import com.rshop.scraper.ScraperConfigException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.select.QueryParser
import org.jsoup.select.Selector

/**
 * Everything site-specific lives here, so a source can be changed without touching code.
 *
 * Each field is a list of rules tried in order (first non-empty result wins), which absorbs
 * minor layout changes. Rule syntax: `css [@attribute] [~regex]`, see [FieldRule].
 */
@Serializable
data class ScraperConfig(
    /** Namespace for game ids: lower-case letters, digits and dashes. */
    val id: String,
    val name: String,
    val baseUrl: String,
    /**
     * First catalogue page. When it contains `{page}`, page N is built from the template;
     * otherwise further pages are reached by following [ListRules.nextPage].
     */
    val listUrl: String,
    val firstPageNumber: Int = 1,
    /**
     * Exact URL of the first page when it differs from the template (`/roms` vs `/roms?page=1`):
     * many sites only serve page 1 at its plain address.
     */
    val firstPageUrl: String? = null,
    /** Optional remote search URL containing `{query}`. */
    val searchUrl: String? = null,
    /** Maximum pages read per listing (per section when [sections] is set). */
    val maxPages: Int = 100,
    /** Set for sites where you first pick a console, then browse that console's games. */
    val sections: SectionRules? = null,
    /**
     * Pages of the consoles to read (as listed by the section index); null reads every console.
     * Only meaningful with [sections].
     */
    val enabledSections: List<String>? = null,
    val list: ListRules,
    val details: DetailRules = DetailRules(),
    /** Hosts allowed for download links (`*.example.com` accepted). Empty: same host as [baseUrl]. */
    val allowedDownloadHosts: List<String> = emptyList(),
    /** Minimum delay between two requests to the site. Never below [MIN_INTERVAL_MS]. */
    val minRequestIntervalMs: Long = 800,
    /**
     * Wait imposed by the site on its download page before the file link may be used
     * (countdown pages). Honored, never skipped.
     */
    val downloadPageDelaySeconds: Int = 0,
    /** Download pages followed at most between the game page and the file (page → page → file). */
    val maxDownloadHops: Int = 3,
    /** Hard cap on requests for one catalogue crawl, so a mis-detected site can never be crawled endlessly. */
    val maxRequestsPerCrawl: Int = 50_000,
) {
    val base: HttpUrl get() = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Invalid baseUrl" }

    val usesPageTemplate: Boolean get() = PAGE_PLACEHOLDER in listUrl

    /** Throws [ScraperConfigException] listing every problem found. */
    fun validate(): ScraperConfig {
        val problems = mutableListOf<String>()
        if (!ID_PATTERN.matches(id)) problems += "id must match ${ID_PATTERN.pattern}"
        if (name.isBlank()) problems += "name is empty"
        val parsedBase = baseUrl.toHttpUrlOrNull()
        if (parsedBase == null) problems += "baseUrl is not an http(s) URL"
        if (parsedBase != null && parsedBase.resolve(listUrl.replace(PAGE_PLACEHOLDER, "1")) == null) {
            problems += "listUrl cannot be resolved against baseUrl"
        }
        if (searchUrl != null && QUERY_PLACEHOLDER !in searchUrl) problems += "searchUrl must contain $QUERY_PLACEHOLDER"
        if (maxPages !in 1..MAX_PAGES) problems += "maxPages must be within 1..$MAX_PAGES"
        if (minRequestIntervalMs < MIN_INTERVAL_MS) problems += "minRequestIntervalMs must be at least $MIN_INTERVAL_MS"
        if (downloadPageDelaySeconds !in 0..120) problems += "downloadPageDelaySeconds must be within 0..120"
        if (maxDownloadHops !in 0..5) problems += "maxDownloadHops must be within 0..5"
        if (maxRequestsPerCrawl < 1) problems += "maxRequestsPerCrawl must be at least 1"
        if (list.item.isEmpty()) problems += "list.item needs at least one selector"
        if (list.title.isEmpty()) problems += "list.title needs at least one rule"
        if (enabledSections != null) {
            if (sections == null) problems += "enabledSections needs sections"
            if (enabledSections.isEmpty()) problems += "enabledSections is empty: choose at least one console"
        }
        if (sections != null) {
            if (sections.item.isEmpty()) problems += "sections.item needs at least one selector"
            if (sections.pageSuffix != null && PAGE_PLACEHOLDER !in sections.pageSuffix) {
                problems += "sections.pageSuffix must contain $PAGE_PLACEHOLDER"
            }
            sections.linkPattern?.let { pattern ->
                try {
                    Regex(pattern)
                } catch (e: java.util.regex.PatternSyntaxException) {
                    problems += "sections.linkPattern: invalid regex (${e.description})"
                }
            }
            sections.item.forEach { css ->
                try {
                    QueryParser.parse(css)
                } catch (e: Selector.SelectorParseException) {
                    problems += "sections.item: invalid selector '$css' (${e.message})"
                }
            }
        }
        list.linkPattern?.let { pattern ->
            try {
                Regex(pattern)
            } catch (e: java.util.regex.PatternSyntaxException) {
                problems += "list.linkPattern: invalid regex (${e.description})"
            }
        }
        if (sections == null && !usesPageTemplate && list.nextPage.isEmpty() && list.indexPages.isEmpty() && maxPages > 1) {
            problems += "listUrl has no $PAGE_PLACEHOLDER and list.nextPage is empty: only one page can be read"
        }
        allRules().forEach { (field, rule) ->
            try {
                FieldRule.parse(rule)
            } catch (e: IllegalArgumentException) {
                problems += "$field: ${e.message}"
            }
        }
        list.item.forEach { css ->
            try {
                QueryParser.parse(css)
            } catch (e: Selector.SelectorParseException) {
                problems += "list.item: invalid selector '$css' (${e.message})"
            }
        }
        if (problems.isNotEmpty()) throw ScraperConfigException(problems)
        return this
    }

    private fun allRules(): List<Pair<String, String>> = buildList {
        fun add(field: String, rules: List<String>) = rules.forEach { add(field to it) }
        sections?.let {
            add("sections.link", it.link)
            add("sections.name", it.name)
        }
        add("list.link", list.link)
        add("list.title", list.title)
        add("list.cover", list.cover)
        add("list.platform", list.platform)
        add("list.genre", list.genre)
        add("list.version", list.version)
        add("list.size", list.size)
        add("list.downloadCount", list.downloadCount)
        add("list.nextPage", list.nextPage)
        add("list.indexPages", list.indexPages)
        add("details.title", details.title)
        add("details.description", details.description)
        add("details.cover", details.cover)
        add("details.screenshots", details.screenshots)
        add("details.downloads", details.downloads)
        add("details.downloadPage", details.downloadPage)
        add("details.size", details.size)
        add("details.downloadCount", details.downloadCount)
        add("details.version", details.version)
        add("details.sha256", details.sha256)
        add("details.platform", details.platform)
        add("details.genre", details.genre)
        add("details.updatedAt", details.updatedAt)
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val PAGE_PLACEHOLDER = "{page}"
        const val QUERY_PLACEHOLDER = "{query}"
        const val MIN_INTERVAL_MS = 400L
        const val MAX_PAGES = 2_000
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,47}")

        val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        /** Parses and validates; throws [ScraperConfigException] or a serialization exception. */
        fun fromJson(json: String): ScraperConfig = JSON.decodeFromString(serializer(), json).validate()
    }
}

/**
 * Console index: [url] lists the consoles, each [item] gives a section link and its name.
 * Inside a section, pages come from [ListRules.nextPage] or, if set, from [pageSuffix]
 * appended to the section URL (e.g. `?page={page}` or `page/{page}/`).
 */
@Serializable
data class SectionRules(
    val url: String,
    val item: List<String>,
    val link: List<String> = listOf("@href", "a[href] @href"),
    val name: List<String> = listOf("@text"),
    val pageSuffix: String? = null,
    /**
     * Regex on the link path (and query): only matching links are consoles. Used when the
     * consoles are found in a navigation menu that also holds other links (Home, FAQ…).
     */
    val linkPattern: String? = null,
)

@Serializable
data class ListRules(
    /** CSS selectors for one game card each; the first selector that matches anything wins. */
    val item: List<String>,
    val link: List<String> = listOf("a[href] @href", "@href"),
    val title: List<String> = listOf("h1, h2, h3, h4", "[title] @title", "img[alt] @alt", "a"),
    val cover: List<String> = listOf("img @data-src", "img @data-lazy-src", "img @src"),
    val platform: List<String> = emptyList(),
    val genre: List<String> = emptyList(),
    val version: List<String> = emptyList(),
    val size: List<String> = emptyList(),
    /** Download counter shown on the card ("12 345 downloads"); see CountParser. */
    val downloadCount: List<String> = emptyList(),
    /** Evaluated on the whole page; must yield the URL of the next page. */
    val nextPage: List<String> = emptyList(),
    /** Optional regex on the link path (and query): items whose link does not match are skipped. */
    val linkPattern: String? = null,
    /**
     * Evaluated on the whole page; yields the links of the listing's index (A, B, C… or
     * 1, 2, 3…). All rules are merged. Every index page found is read once, as are the index
     * links on those pages.
     */
    val indexPages: List<String> = emptyList(),
) {
    companion object {
        /** Common ways sites mark the "next page" link (used by the analyzer and remote search). */
        /** Numbered links of a pager ("1 2 3 … 40"), whatever their URLs look like. */
        const val NUMBERED_PAGER = ":is([class*=pag], [id*=pag], nav[aria-label]) a[href]:matchesOwn(^\\s*\\d\\d?\\d?\\d?\\s*$) @href"

        val DEFAULT_NEXT_PAGE = listOf(
            "link[rel=next] @href",
            "a[rel=next] @href",
            "a.next @href",
            "a[class*=next] @href",
            "[class*=next] a @href",
            "a[aria-label~=(?i)(next|suivant)] @href",
            "a[title~=(?i)(next|suivant)] @href",
            "a:matchesOwn((?i)^\\s*(next|next page|suivant|suivante|page suivante|›|»|>|>>|→)\\s*$) @href",
            "a:matches((?i)^\\s*(next|suivant|page suivante)\\b) @href",
        )
    }
}

@Serializable
data class DetailRules(
    val title: List<String> = listOf("h1", "meta[property=og:title] @content", "title"),
    val description: List<String> = listOf(
        "[itemprop=description]",
        "meta[property=og:description] @content",
        "meta[name=description] @content",
    ),
    val cover: List<String> = listOf("meta[property=og:image] @content"),
    val screenshots: List<String> = emptyList(),
    /** All matches are kept (several files per game). */
    val downloads: List<String> = listOf(DEFAULT_DOWNLOAD_RULE, "a[download][href] @href"),
    /**
     * When the game page has no direct file link: link to the site's download page, where the
     * [downloads] rules find the file link after [ScraperConfig.downloadPageDelaySeconds].
     */
    val downloadPage: List<String> = emptyList(),
    val size: List<String> = emptyList(),
    val version: List<String> = emptyList(),
    val sha256: List<String> = listOf("body ~(?i)sha-?256\\W{0,20}([a-f0-9]{64})"),
    val platform: List<String> = emptyList(),
    val genre: List<String> = listOf("[itemprop=genre]"),
    val updatedAt: List<String> = listOf(
        "meta[property=article:modified_time] @content",
        "time[datetime] @datetime",
    ),
    /** Download counter on the game page ("Downloads: 12,345", "1.2k téléchargements"). */
    val downloadCount: List<String> = DEFAULT_DOWNLOAD_COUNT,
) {
    companion object {
        /** Words sites put next to a download counter. */
        const val COUNT_WORDS = "(?:downloads?|téléchargements?|téléchargé|dls?)"

        /** "Downloads: 12,345" or "12,345 downloads", in any element's text. */
        val DEFAULT_DOWNLOAD_COUNT = listOf(
            "body ~(?i)$COUNT_WORDS\\s*[:：]\\s*${com.rshop.scraper.parse.CountParser.NUMBER}",
            "body ~(?i)${com.rshop.scraper.parse.CountParser.NUMBER}\\s+$COUNT_WORDS\\b",
        )

        /** A button labelled "Download" / "Télécharger", or an input whose value says so. */
        private const val BUTTON =
            ":is(button:matches((?i)^\\W*(download|télécharger)\\b), [role=button]:matches((?i)^\\W*(download|télécharger)\\b), " +
                "input[type=button][value~=(?i)^\\W*(download|télécharger)\\b], input[type=submit][value~=(?i)^\\W*(download|télécharger)\\b])"

        /** `location.href = '…'`, `location.assign('…')`, `window.open('…')`: the quoted URL. */
        private const val NAVIGATION_HANDLER =
            "(?:location(?:\\.href)?\\s*=|location\\.(?:assign|replace)\\s*\\(|window\\.open\\s*\\()\\s*['\"]([^'\"]+)['\"]"

        /** Links whose path ends with a typical archive or ROM extension. */
        val DOWNLOAD_EXTENSIONS = listOf(
            // Archives
            "zip", "7z", "tar", "gz", "tgz", "xz", "bz2", "zst", "lzma", "rar",
            // Cartridge and computer ROMs
            "nes", "fds", "sfc", "smc", "gb", "gbc", "gba", "nds", "dsi", "n64", "z64", "v64",
            "md", "gen", "smd", "32x", "sms", "gg", "sg", "sc", "pce", "sgx", "a26", "a52", "a78", "lnx", "jag", "j64",
            "ws", "wsc", "ngp", "ngc", "vb", "vboy", "col", "vec", "int", "mx1", "mx2", "min", "rom", "bin",
            "dsk", "adf", "d64", "t64", "tap", "tzx", "st", "msa", "atr", "xex", "cas",
            // Disc images and console packages
            "iso", "chd", "cue", "gdi", "cdi", "mdf", "mds", "nrg", "ccd", "ecm", "pbp", "cso", "zso", "ciso",
            "rvz", "wbfs", "gcz", "wia", "gcm", "wad", "xiso",
            "3ds", "cia", "cci", "cxi", "pkg", "rap", "psv", "vpk", "nsp", "xci", "nca", "nro", "wua", "wux",
        )
        /**
         * Download buttons that lead to a download page or redirect to the file. Always tried
         * after [downloadPage], so configs saved before these rules existed still find them.
         */
        val DEFAULT_DOWNLOAD_BUTTONS = listOf(
            "a:matchesOwn((?i)^\\s*(download|télécharger)\\b) @href",
            // Label inside child spans/icons: "<a><span>💾</span><span>Download ROM</span></a>".
            "a:matches((?i)^\\W*(download|télécharger)\\b) @href",
            "a[class*=download] @href",
            "[class*=download] a[href] @href",
            "a[href*=download] @href",
            // <button> carrying its target in an attribute or a plain navigation handler
            // (read as text, never run): onclick="location.href='/dl/42'", data-href="/dl/42".
            "$BUTTON[formaction] @formaction",
            "$BUTTON[data-href] @data-href",
            "$BUTTON[data-url] @data-url",
            "$BUTTON[data-link] @data-link",
            "$BUTTON[data-download] @data-download",
            "$BUTTON[onclick] @onclick ~$NAVIGATION_HANDLER",
        )

        val DEFAULT_DOWNLOAD_RULE: String =
            DOWNLOAD_EXTENSIONS.joinToString(", ") { "a[href$=.$it]" } + " @href"
    }
}
