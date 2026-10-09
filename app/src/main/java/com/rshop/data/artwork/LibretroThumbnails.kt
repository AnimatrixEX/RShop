package com.rshop.data.artwork

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The pictures the Libretro server keeps per game, each in its own folder. */
enum class ThumbnailKind(val folder: String) {
    Boxart("Named_Boxarts"),
    /** A screenshot taken during play. */
    Snap("Named_Snaps"),
    /** The title screen. */
    Title("Named_Titles"),
}

/**
 * Pictures from the Libretro thumbnails server (box art, screenshots, title screens), which needs no key. Per console the server lists
 * its files once (about 200 KB compressed for the biggest ones); that list is kept on the device
 * and games are matched against it, so finding a cover costs no request at all.
 */
@Singleton
class LibretroThumbnails @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    private val clock: Clock,
) {
    private val client = okHttpClient.newBuilder().cache(null).build()
    private val dir = File(context.filesDir, "libretro")
    private val indexes = ConcurrentHashMap<String, LibretroIndex>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Overridable in tests. */
    internal var baseUrl: HttpUrl = BASE_URL.toHttpUrl()

    /**
     * The address of the picture of [title] on [platform], or null when the console is unknown to
     * Libretro or it has none for that game. Network trouble propagates (try again later).
     */
    suspend fun find(platform: String?, title: String, kind: ThumbnailKind = ThumbnailKind.Boxart): String? {
        val system = LibretroSystems.directoryFor(platform) ?: return null
        val name = indexOf(system, kind).match(title) ?: return null
        return baseUrl.newBuilder().addPathSegment(system).addPathSegment(kind.folder).addPathSegment("$name.png").build().toString()
    }

    /** A screenshot and the title screen of the game, those the server has (possibly none). */
    suspend fun findScreenshots(platform: String?, title: String): List<String> =
        listOfNotNull(find(platform, title, ThumbnailKind.Snap), find(platform, title, ThumbnailKind.Title))

    private suspend fun indexOf(system: String, kind: ThumbnailKind): LibretroIndex {
        val key = "$system/${kind.folder}"
        indexes[key]?.let { return it }
        return locks.getOrPut(key) { Mutex() }.withLock {
            indexes[key] ?: load(system, kind).also { indexes[key] = it }
        }
    }

    private suspend fun load(system: String, kind: ThumbnailKind): LibretroIndex = withContext(Dispatchers.IO) {
        val file = File(dir, "${"$system/${kind.folder}".hashCode().toUInt().toString(16)}.txt")
        val age = clock.millis() - file.lastModified()
        if (file.isFile && age in 0..MAX_AGE_MS) {
            return@withContext LibretroIndex(file.readLines().filter { it.isNotBlank() })
        }
        try {
            val names = download(system, kind)
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(names.joinToString("\n"))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            LibretroIndex(names)
        } catch (e: IOException) {
            // A stale list beats none when the server cannot be reached.
            if (file.isFile) return@withContext LibretroIndex(file.readLines().filter { it.isNotBlank() })
            throw e
        }
    }

    private fun download(system: String, kind: ThumbnailKind): List<String> {
        val url = baseUrl.newBuilder().addPathSegment(system).addPathSegment(kind.folder).addPathSegment("").build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            // A console the server has no folder for: an empty list, kept so it is not asked again.
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("Libretro thumbnails: HTTP ${response.code}")
            val names = LibretroIndex.parseListing(response.body.string())
            Timber.i("Libretro %s: %d box arts", system, names.size)
            return names
        }
    }

    private companion object {
        const val BASE_URL = "https://thumbnails.libretro.com/"
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(60)
    }
}

/** The box arts one console has, and how a game title is matched to one of them. */
class LibretroIndex(names: List<String>) {
    private val byKey: Map<String, List<String>> = names.groupBy { key(it) }

    /** The file name (without ".png") of the best box art for [title], or null. */
    fun match(title: String): String? {
        val candidates = byKey[key(title)] ?: return null
        val wanted = regions(title)
        return candidates.maxByOrNull { score(it, wanted) }
    }

    private fun score(name: String, wanted: Set<String>): Int {
        var score = 0
        val own = regions(name)
        // The region the title names, else the usual order of preference.
        score += if (wanted.isNotEmpty()) (if (own.any { it in wanted }) 100 else 0) else own.maxOfOrNull { REGION_ORDER[it] ?: 0 } ?: 0
        if (UNFINISHED.containsMatchIn(name)) score -= 60
        if (DISC_ONE.containsMatchIn(name)) score += 5
        return score
    }

    companion object {
        private val HREF = Regex("href=\"([^\"?/][^\"]*?)\\.png\"")
        private val UNFINISHED = Regex("(?i)\\((beta|proto|demo|sample|unl|pirate|kiosk)")
        private val DISC_ONE = Regex("(?i)\\((disc|disk|cd) ?1\\)")
        private val REGION_TOKEN = Regex("\\(([^)]*)\\)")
        private val KNOWN_REGIONS = setOf("usa", "world", "europe", "japan", "korea", "asia", "australia", "brazil", "germany", "france", "spain", "italy")
        private val REGION_ORDER = mapOf("usa" to 50, "world" to 45, "europe" to 40, "japan" to 30)

        /** File names (decoded, without ".png") of an Apache directory listing. */
        fun parseListing(html: String): List<String> = HREF.findAll(html)
            .map { decode(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .toList()

        private fun decode(encoded: String): String =
            runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrDefault(encoded)

        private fun regions(text: String): Set<String> = REGION_TOKEN.findAll(text)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim().lowercase() }
            .filter { it in KNOWN_REGIONS }
            .toSet()

        /** Same game whatever the region, punctuation, case, "Name, The" order or file extension. */
        fun key(text: String): String = ArtworkTitle.searchTerm(text).lowercase().filter { it.isLetterOrDigit() }
    }
}
