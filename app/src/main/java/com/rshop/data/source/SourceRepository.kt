package com.rshop.data.source

import android.content.Context
import com.rshop.BuildConfig
import com.rshop.di.ApplicationScope
import com.rshop.scraper.ScraperLog
import com.rshop.scraper.analysis.SiteAnalyzer
import com.rshop.scraper.GameSource
import com.rshop.scraper.config.DriveConfig
import com.rshop.scraper.config.ScraperConfig
import com.rshop.scraper.config.SourceConfig
import com.rshop.scraper.drive.DriveApi
import com.rshop.scraper.drive.DriveSource
import com.rshop.scraper.http.HtmlFetcher
import com.rshop.scraper.http.RateLimiter
import com.rshop.scraper.website.WebsiteSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The catalogue sources (any number, one JSON file each in app-private storage), plus the shared
 * polite HTTP client every scraper request goes through. A source's id is its site's host, so
 * adding the same site again updates it instead of duplicating it.
 */
@Singleton
class SourceRepository @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    @ApplicationScope scope: CoroutineScope,
    private val driveSettings: DriveSettings,
) {
    private val dir = File(context.filesDir, "source/sources")
    /** Single-source storage of earlier versions, moved into [dir] on first start. */
    private val legacyFile = File(context.filesDir, "source/config.json")
    private val state = MutableStateFlow<List<SourceConfig>>(emptyList())
    private val loaded = scope.async(Dispatchers.IO) { state.value = readAll() }

    /** All sources, by name. */
    val configs: Flow<List<SourceConfig>> = flow {
        loaded.await()
        emitAll(state)
    }

    suspend fun all(): List<SourceConfig> {
        loaded.await()
        return state.value
    }

    suspend fun get(id: String): SourceConfig? = all().firstOrNull { it.id == id }

    // Pages must be read fresh; images keep their own Coil cache.
    private val client = okHttpClient.newBuilder().cache(null).build()
    private val rateLimiter = RateLimiter()

    /** One fetcher (one rate limiter, one robots.txt cache) shared by sync, details and analysis. */
    private val fetcher = HtmlFetcher(
        client = client,
        userAgent = "RShop/${BuildConfig.VERSION_NAME} (Android; catalogue reader)",
        productToken = "RShop",
        rateLimiter = rateLimiter,
        log = TimberScraperLog,
    )

    fun createSource(config: SourceConfig): GameSource = when (config) {
        is ScraperConfig -> WebsiteSource(config, fetcher, TimberScraperLog)
        is DriveConfig -> createDriveSource(config)
        else -> error("Unknown source type ${config::class.simpleName}")
    }

    /** The Drive API key comes from [DriveSettings]; it is added to each request by the client. */
    fun createDriveSource(config: DriveConfig) = DriveSource(
        config,
        DriveApi(client, rateLimiter, driveSettings::awaitCredentials, config.minRequestIntervalMs.milliseconds, log = TimberScraperLog),
        TimberScraperLog,
    )

    fun analyzer() = SiteAnalyzer(fetcher, TimberScraperLog)

    /** Adds the source, or replaces the one with the same id. */
    suspend fun save(config: SourceConfig) {
        config.validate()
        loaded.await()
        withContext(Dispatchers.IO) { write(config) }
        state.update { list -> (list.filterNot { it.id == config.id } + config).sortedBy { it.name.lowercase() } }
        Timber.i("Catalogue source saved: %s (%s)", config.name, config.location)
    }

    suspend fun remove(id: String) {
        loaded.await()
        withContext(Dispatchers.IO) { File(dir, "$id.json").delete() }
        state.update { list -> list.filterNot { it.id == id } }
    }

    private fun write(config: SourceConfig) {
        dir.mkdirs()
        val file = File(dir, "${config.id}.json")
        val tmp = File(dir, "${config.id}.json.tmp")
        tmp.writeText(config.toJson())
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Cannot write ${file.path}")
        }
    }

    private fun readAll(): List<SourceConfig> {
        migrateLegacy()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
        return files.mapNotNull(::read).map(::faster).distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    /** Drive sources were first saved with the 400 ms interval a website needs: the Drive API takes far more. */
    private fun faster(config: SourceConfig): SourceConfig =
        if (config is DriveConfig && config.minRequestIntervalMs == 400L) config.withInterval(DriveConfig.DEFAULT_INTERVAL_MS) else config

    private fun migrateLegacy() {
        if (!legacyFile.exists()) return
        val config = read(legacyFile)
        if (config != null) {
            runCatching { write(config) }.onFailure { Timber.e(it, "Cannot migrate the source config") }.getOrNull() ?: return
        }
        legacyFile.delete()
    }

    private fun read(file: File): SourceConfig? = try {
        SourceConfig.fromJson(file.readText())
    } catch (e: SerializationException) {
        Timber.e(e, "Source config %s is unreadable", file.name)
        null
    } catch (e: IllegalArgumentException) {
        Timber.e(e, "Source config %s is invalid", file.name)
        null
    } catch (e: IOException) {
        Timber.e(e, "Source config %s cannot be read", file.name)
        null
    }
}

/** Name to show for each source id: the site name, with its host when two sources share a name. */
fun List<SourceConfig>.displayNames(): Map<String, String> {
    val shared = groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
    return associate { config ->
        val host = if (config is DriveConfig) "Google Drive" else config.location.substringAfter("://").substringBefore('/')
        config.id to if (config.name.lowercase() in shared) "${config.name} ($host)" else config.name
    }
}

object TimberScraperLog : ScraperLog {
    override fun debug(message: String) = Timber.tag("Scraper").d(message)
    override fun warn(message: String, error: Throwable?) = Timber.tag("Scraper").w(error, message)
}
