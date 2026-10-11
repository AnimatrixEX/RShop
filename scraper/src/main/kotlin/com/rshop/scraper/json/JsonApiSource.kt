package com.rshop.scraper.json

import com.rshop.scraper.GameSource
import com.rshop.scraper.ScraperException
import com.rshop.scraper.ScraperLog
import com.rshop.scraper.config.JsonApiConfig
import com.rshop.scraper.http.JsonFetcher
import com.rshop.scraper.model.CatalogPage
import com.rshop.scraper.model.ScrapedDownload
import com.rshop.scraper.model.ScrapedGame
import com.rshop.scraper.model.ScrapedGameDetails
import com.rshop.scraper.parse.CountParser
import com.rshop.scraper.parse.DateParser
import com.rshop.scraper.parse.Sha256Parser
import com.rshop.scraper.parse.SizeParser
import com.rshop.scraper.website.DownloadUrlPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds

/**
 * A catalogue read from a JSON API ([JsonApiConfig]). The JSON never reaches the rest of the app:
 * it is turned into the same scraped models as a website.
 */
class JsonApiSource(
    val config: JsonApiConfig,
    private val fetcher: JsonFetcher,
    private val log: ScraperLog = ScraperLog.None,
) : GameSource {

    override val id: String get() = config.id
    override val name: String get() = config.name

    /** Relative links resolve against a folder: `https://x/api` means `https://x/api/`. */
    private val base: HttpUrl = config.base.let { url ->
        if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
    }
    private val interval = config.minRequestIntervalMs.milliseconds
    private val downloadPolicy = DownloadUrlPolicy(base, config.allowedDownloadHosts)

    private val rules = config.list
    private val items = rules.items.map(JsonPath::parse)
    private val idPaths = rules.id.map(JsonPath::parse)
    private val titlePaths = rules.title.map(JsonPath::parse)
    private val coverPaths = rules.cover.map(JsonPath::parse)
    private val platformPaths = rules.platform.map(JsonPath::parse)
    private val genrePaths = rules.genre.map(JsonPath::parse)
    private val versionPaths = rules.version.map(JsonPath::parse)
    private val sizePaths = rules.size.map(JsonPath::parse)
    private val countPaths = rules.downloadCount.map(JsonPath::parse)
    private val hasNextPaths = rules.hasNext.map(JsonPath::parse)
    private val totalPagesPaths = rules.totalPages.map(JsonPath::parse)

    private val detailRules = config.details
    private val detailRoots = detailRules.root.map(JsonPath::parse)
    private val descriptionPaths = detailRules.description.map(JsonPath::parse)
    private val screenshotPaths = detailRules.screenshots.map(JsonPath::parse)
    private val updatedPaths = detailRules.updatedAt.map(JsonPath::parse)
    private val downloadsPaths = detailRules.downloads.map(JsonPath::parse)
    private val fileRules = detailRules.download
    private val fileUrlPaths = fileRules.url.map(JsonPath::parse)
    private val fileNamePaths = fileRules.fileName.map(JsonPath::parse)
    private val fileSizePaths = fileRules.size.map(JsonPath::parse)
    private val fileShaPaths = fileRules.sha256.map(JsonPath::parse)
    private val fileLabelPaths = fileRules.label.map(JsonPath::parse)
    private val fileUpdatePaths = fileRules.isUpdate.map(JsonPath::parse)
    private val fileExtraPaths = fileRules.isExtra.map(JsonPath::parse)

    /** Ids of the first page, to notice an API that ignores the page number and repeats itself. */
    @Volatile
    private var firstPageIds: Set<String>? = null

    override fun acceptsDownloadUrl(url: HttpUrl): Boolean = downloadPolicy.accepts(url)

    override suspend fun getPage(page: Int): CatalogPage {
        if (page >= config.maxPages) return CatalogPage(emptyList(), hasNext = false)
        val number = config.firstPageNumber + page
        val url = resolve(
            config.listUrl.replace(JsonApiConfig.PAGE, number.toString()).replace(JsonApiConfig.OFFSET, (page.toLong() * config.pageSize).toString()),
        ) ?: throw ScraperException.InvalidContent(config.listUrl, "listUrl does not make a valid URL")
        val root = fetcher.fetch(url, interval)
        val games = gamesOf(url.toString(), root)

        val ids = games.mapTo(HashSet()) { it.id }
        if (page == 0) firstPageIds = ids
        else if (ids.isNotEmpty() && ids == firstPageIds) {
            log.warn("${config.name}: page $number repeats the first page, the API ignores the page number")
            return CatalogPage(emptyList(), hasNext = false)
        }
        val hasNext = config.usesPaging && page + 1 < config.maxPages && hasNextPage(root, games.size, number)
        return CatalogPage(games, hasNext)
    }

    override suspend fun getGameDetails(id: String): ScrapedGameDetails {
        val url = resolve(config.detailsUrl.replace(JsonApiConfig.ID, encode(id)))
            ?: throw ScraperException.InvalidContent(config.detailsUrl, "detailsUrl does not make a valid URL")
        val response = fetcher.fetch(url, interval)
        val game = detailRoots.firstNotNullOfOrNull { path -> path.first(response) as? JsonObject }
            ?: throw ScraperException.StructureChanged(url.toString(), "no game found: check details.root")
        val scraped = (toGame(game) ?: ScrapedGame(id = id, title = id)).copy(id = id)
        val screenshots = screenshotPaths.firstNotNullOfOrNull { path ->
            path.select(game).mapNotNull { it.text()?.let(::link) }.distinct().takeIf { it.isNotEmpty() }
        }.orEmpty()
        return ScrapedGameDetails(
            game = scraped,
            description = text(game, descriptionPaths),
            screenshots = screenshots,
            downloads = downloadsOf(game, url.toString()),
            updatedAt = DateParser.parse(text(game, updatedPaths)),
        )
    }

    override suspend fun search(query: String): List<ScrapedGame> {
        val template = config.searchUrl ?: return emptyList()
        val url = resolve(template.replace(JsonApiConfig.QUERY, encode(query)))
            ?: throw ScraperException.InvalidContent(template, "searchUrl does not make a valid URL")
        return gamesOf(url.toString(), fetcher.fetch(url, interval))
    }

    private fun gamesOf(url: String, root: JsonElement): List<ScrapedGame> {
        val array = items.firstNotNullOfOrNull { path -> path.first(root) as? JsonArray }
            ?: throw ScraperException.StructureChanged(url, "no list of games found: check list.items")
        return array.mapNotNull { (it as? JsonObject)?.let(::toGame) }.distinctBy { it.id }
    }

    private fun toGame(item: JsonObject): ScrapedGame? {
        val id = text(item, idPaths) ?: return null
        val title = text(item, titlePaths) ?: return null
        return ScrapedGame(
            id = id,
            title = title,
            coverUrl = text(item, coverPaths)?.let(::link),
            platform = text(item, platformPaths),
            genre = text(item, genrePaths),
            version = text(item, versionPaths),
            sizeBytes = number(item, sizePaths, SizeParser::parse),
            downloadCount = number(item, countPaths, CountParser::parse),
        )
    }

    private fun downloadsOf(game: JsonObject, pageUrl: String): List<ScrapedDownload> {
        val entries = downloadsPaths.firstNotNullOfOrNull { path -> path.first(game) as? JsonArray } ?: return emptyList()
        return entries.mapNotNull { entry ->
            // An entry may be the bare link, or an object describing the file.
            val file = entry as? JsonObject
            val raw = if (file == null) entry.text() else text(file, fileUrlPaths)
            val url = raw?.let(::link)?.toHttpUrl()
            when {
                url == null -> null
                !downloadPolicy.accepts(url) -> {
                    log.warn("Download link ${url.host} left out of $pageUrl: host not allowed (allowedDownloadHosts)")
                    null
                }
                file == null -> ScrapedDownload(url.toString())
                else -> ScrapedDownload(
                    url = url.toString(),
                    fileName = text(file, fileNamePaths),
                    sizeBytes = number(file, fileSizePaths, SizeParser::parse),
                    sha256 = Sha256Parser.parse(text(file, fileShaPaths)),
                    label = text(file, fileLabelPaths),
                    isUpdate = flag(file, fileUpdatePaths),
                    isExtra = flag(file, fileExtraPaths),
                )
            }
        }.distinctBy { it.url }
    }

    private fun hasNextPage(root: JsonElement, count: Int, number: Int): Boolean {
        for (path in hasNextPaths) {
            val value = path.first(root) ?: continue
            return when (value) {
                is JsonPrimitive -> value.content.let { it.isNotBlank() && !it.equals("false", true) && it != "0" }
                is JsonArray -> value.isNotEmpty()
                is JsonObject -> value.isNotEmpty()
            }
        }
        for (path in totalPagesPaths) {
            val total = (path.first(root) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: continue
            return number < total
        }
        return count > 0
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun resolve(value: String): HttpUrl? = base.resolve(value)?.takeIf { it.scheme == "https" || it.scheme == "http" }

    private fun link(value: String): String? = resolve(value)?.toString()

    private fun text(element: JsonElement, paths: List<JsonPath>): String? =
        paths.firstNotNullOfOrNull { it.first(element)?.display() }

    private fun number(element: JsonElement, paths: List<JsonPath>, parse: (String) -> Long?): Long? =
        paths.firstNotNullOfOrNull { path -> path.first(element)?.let { (it as? JsonPrimitive)?.text() }?.let(parse) }

    private fun flag(element: JsonElement, paths: List<JsonPath>): Boolean =
        text(element, paths)?.let { it.equals("true", true) || it == "1" } ?: false

    /** Percent-encodes a value for a URL path or query. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
