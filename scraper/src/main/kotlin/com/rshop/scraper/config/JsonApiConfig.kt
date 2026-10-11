package com.rshop.scraper.config

import com.rshop.scraper.ScraperConfigException
import com.rshop.scraper.json.JsonPath
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A catalogue served as JSON by an API the owner makes for apps. Like [ScraperConfig] for
 * websites, everything specific to the API lives here: where the lists are, how pages are asked
 * for, and which JSON path holds each field ([com.rshop.scraper.json.JsonPath]; every field is a
 * list of paths, the first one that finds a value wins). The defaults describe RShop's own
 * catalogue format, so a server written for RShop only needs `baseUrl` and `listUrl`.
 *
 * ```json
 * { "type": "json", "id": "my-api", "name": "My catalogue",
 *   "baseUrl": "https://example.com/api/", "listUrl": "games?page={page}",
 *   "detailsUrl": "games/{id}", "searchUrl": "games?q={query}" }
 * ```
 */
@Serializable
data class JsonApiConfig(
    override val id: String,
    override val name: String,
    /** Base of every relative URL (list, details, covers, files). */
    val baseUrl: String,
    /** The catalogue. `{page}` is the page number, `{offset}` the number of games before the page. */
    val listUrl: String,
    val firstPageNumber: Int = 1,
    /** Games per page, used to compute `{offset}` (and to detect the end when the API gives no hint). */
    val pageSize: Int = 50,
    /** One game: `{id}` is replaced by the game's id. */
    val detailsUrl: String,
    /** Optional remote search containing `{query}`. */
    val searchUrl: String? = null,
    /** Maximum pages read in one crawl. */
    val maxPages: Int = 1_000,
    val list: JsonListRules = JsonListRules(),
    val details: JsonDetailRules = JsonDetailRules(),
    /** Hosts allowed for download links (`*.example.com` accepted). Empty: the API's host and subdomains. */
    val allowedDownloadHosts: List<String> = emptyList(),
    override val minRequestIntervalMs: Long = 400,
    /** Tells a stored JSON source from the other kinds. */
    val type: String = TYPE,
) : SourceConfig {

    override val location: String get() = baseUrl

    /** A JSON catalogue has no console index: every game names its own platform. */
    override val enabledSections: List<String>? get() = null

    override fun withEnabledSections(sections: List<String>?): JsonApiConfig = this

    override fun withInterval(intervalMs: Long): JsonApiConfig = copy(minRequestIntervalMs = intervalMs)

    val base: HttpUrl get() = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Invalid baseUrl" }

    val usesPaging: Boolean get() = PAGE in listUrl || OFFSET in listUrl

    override fun validate(): JsonApiConfig {
        val problems = mutableListOf<String>()
        if (!SourceConfig.ID_PATTERN.matches(id)) problems += "id must match ${SourceConfig.ID_PATTERN.pattern}"
        if (name.isBlank()) problems += "name is empty"
        val parsedBase = baseUrl.toHttpUrlOrNull()
        if (parsedBase == null) problems += "baseUrl is not an http(s) URL"
        if (parsedBase != null) {
            if (parsedBase.resolve(listUrl.replace(PAGE, "1").replace(OFFSET, "0")) == null) problems += "listUrl cannot be resolved against baseUrl"
            if (parsedBase.resolve(detailsUrl.replace(ID, "x")) == null) problems += "detailsUrl cannot be resolved against baseUrl"
        }
        if (ID !in detailsUrl) problems += "detailsUrl must contain $ID"
        if (searchUrl != null && QUERY !in searchUrl) problems += "searchUrl must contain $QUERY"
        if (pageSize !in 1..10_000) problems += "pageSize must be within 1..10000"
        if (maxPages !in 1..100_000) problems += "maxPages must be within 1..100000"
        if (minRequestIntervalMs < MIN_INTERVAL_MS) problems += "minRequestIntervalMs must be at least $MIN_INTERVAL_MS"
        if (type != TYPE) problems += "type must be $TYPE"
        if (list.items.isEmpty()) problems += "list.items needs at least one path"
        if (list.id.isEmpty()) problems += "list.id needs at least one path"
        if (list.title.isEmpty()) problems += "list.title needs at least one path"
        allPaths().forEach { (field, path) ->
            try {
                JsonPath.parse(path)
            } catch (e: IllegalArgumentException) {
                problems += "$field: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) throw ScraperConfigException(problems)
        return this
    }

    private fun allPaths(): List<Pair<String, String>> = buildList {
        fun add(field: String, paths: List<String>) = paths.forEach { add(field to it) }
        add("list.items", list.items)
        add("list.id", list.id)
        add("list.title", list.title)
        add("list.cover", list.cover)
        add("list.platform", list.platform)
        add("list.genre", list.genre)
        add("list.version", list.version)
        add("list.size", list.size)
        add("list.downloadCount", list.downloadCount)
        add("list.hasNext", list.hasNext)
        add("list.totalPages", list.totalPages)
        add("details.root", details.root)
        add("details.description", details.description)
        add("details.screenshots", details.screenshots)
        add("details.updatedAt", details.updatedAt)
        add("details.downloads", details.downloads)
        add("details.download.url", details.download.url)
        add("details.download.fileName", details.download.fileName)
        add("details.download.size", details.download.size)
        add("details.download.sha256", details.download.sha256)
        add("details.download.label", details.download.label)
        add("details.download.isUpdate", details.download.isUpdate)
        add("details.download.isExtra", details.download.isExtra)
    }

    override fun toJson(): String = ScraperConfig.JSON.encodeToString(serializer(), this)

    companion object {
        const val TYPE = "json"
        const val MIN_INTERVAL_MS = 100L
        const val PAGE = "{page}"
        const val OFFSET = "{offset}"
        const val ID = "{id}"
        const val QUERY = "{query}"

        fun fromJson(json: String): JsonApiConfig = ScraperConfig.JSON.decodeFromString(serializer(), json).validate()
    }
}

/** Where the games are in a page of the catalogue, and which paths (inside one game) hold each field. */
@Serializable
data class JsonListRules(
    /** The array of games of a page (`""` when the response is the array itself). */
    val items: List<String> = listOf("games", "data", "results", "items", "$"),
    val id: List<String> = listOf("id", "slug", "uuid"),
    val title: List<String> = listOf("title", "name"),
    val cover: List<String> = listOf("cover", "coverUrl", "cover_url", "image", "thumbnail"),
    val platform: List<String> = listOf("platform", "console", "system"),
    val genre: List<String> = listOf("genre", "genres", "category"),
    val version: List<String> = listOf("version"),
    val size: List<String> = listOf("size", "sizeBytes", "size_bytes", "fileSize"),
    val downloadCount: List<String> = listOf("downloads", "downloadCount", "download_count", "popularity"),
    /**
     * Paths (in the page response) telling whether another page follows: a boolean, or a link to
     * the next page (anything not empty/null/false means yes). When none is found, the next page
     * is requested until one comes back empty.
     */
    val hasNext: List<String> = listOf("hasNext", "has_next", "hasMore", "next", "links.next", "meta.next"),
    /** Paths of the total number of pages, used when [hasNext] finds nothing. */
    val totalPages: List<String> = listOf("totalPages", "total_pages", "pages", "meta.totalPages", "meta.pages"),
)

@Serializable
data class JsonDetailRules(
    /** The game inside the details response (`""`: the response itself). Tried in order. */
    val root: List<String> = listOf("game", "data", "$"),
    val description: List<String> = listOf("description", "summary", "synopsis"),
    /** Screenshots: an array of links, or of objects holding one (`screenshots[].url`). */
    val screenshots: List<String> = listOf("screenshots[]", "screenshots[].url", "images[]"),
    val updatedAt: List<String> = listOf("updatedAt", "updated_at", "modified", "lastUpdated"),
    /** The array of files offered by a game. */
    val downloads: List<String> = listOf("downloads", "files", "links"),
    val download: JsonDownloadRules = JsonDownloadRules(),
)

/** Paths, inside one entry of the downloads array, of what describes a file. */
@Serializable
data class JsonDownloadRules(
    val url: List<String> = listOf("url", "href", "link"),
    val fileName: List<String> = listOf("fileName", "file_name", "filename", "name"),
    val size: List<String> = listOf("size", "sizeBytes", "size_bytes"),
    /** Lower- or upper-case hex SHA-256 of the file, verified after the download. */
    val sha256: List<String> = listOf("sha256", "checksum"),
    val label: List<String> = listOf("label", "format", "region"),
    val isUpdate: List<String> = listOf("isUpdate", "update"),
    val isExtra: List<String> = listOf("isExtra", "extra"),
)
