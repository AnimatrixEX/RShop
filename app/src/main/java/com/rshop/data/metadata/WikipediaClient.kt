package com.rshop.data.metadata

import com.rshop.data.artwork.ArtworkTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** A description taken from a Wikipedia article, with where it came from. */
data class WikipediaDescription(
    val text: String,
    /** "wikipedia:en": stored with the description so the page can credit it. */
    val source: String,
    val articleTitle: String,
)

/**
 * Looks a game up on Wikipedia and returns the start of its article. Nothing is guessed: the
 * article must be about a video game and carry the game's name. Wikipedia's text is CC BY-SA, so
 * the description is always shown with its source.
 */
@Singleton
class WikipediaClient @Inject constructor(
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder().cache(null).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Mutex()
    private var lastRequestAt = 0L

    /** Overridable in tests: address of the API for a language. */
    internal var endpoint: (String) -> HttpUrl = { language -> "https://$language.wikipedia.org/w/api.php".toHttpUrl() }

    /** Identifies the app, as Wikipedia's API etiquette asks. */
    internal var userAgent = "RShop (https://github.com/AnimatrixEX/RShop)"

    /** Languages are tried in order; the first article that fits wins. Network trouble propagates. */
    suspend fun describe(title: String, languages: List<String>): WikipediaDescription? {
        val term = ArtworkTitle.searchTerm(title)
        if (term.length < MIN_TERM) return null
        for (language in languages) {
            describeIn(term, language)?.let { return it }
        }
        return null
    }

    private suspend fun describeIn(term: String, language: String): WikipediaDescription? {
        val pages = search(term, language)
        val wanted = key(term)
        for (page in pages.sortedBy { it.index }) {
            val articleKey = key(page.title.substringBefore(" ("))
            val sameName = articleKey == wanted || (wanted.length >= LONG_NAME && articleKey.contains(wanted))
            if (!sameName || !aboutAGame(page)) continue
            val text = tidy(page.extract.orEmpty())
            if (text.length < MIN_TEXT) continue
            return WikipediaDescription(text, "wikipedia:$language", page.title)
        }
        return null
    }

    @Serializable
    private data class Reply(val query: Query? = null)

    @Serializable
    private data class Query(val pages: List<Page> = emptyList())

    @Serializable
    private data class Page(
        val title: String,
        val index: Int = 0,
        val extract: String? = null,
        val description: String? = null,
    )

    private suspend fun search(term: String, language: String): List<Page> = withContext(Dispatchers.IO) {
        val url = endpoint(language).newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", "$term ${if (language == "fr") "jeu vidéo" else "video game"}")
            .addQueryParameter("gsrnamespace", "0")
            .addQueryParameter("gsrlimit", "5")
            .addQueryParameter("prop", "extracts|description")
            .addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("exsentences", "5")
            .addQueryParameter("exlimit", "max")
            .addQueryParameter("redirects", "1")
            .build()
        throttled()
        client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Wikipedia: HTTP ${response.code}")
            json.decodeFromString(Reply.serializer(), response.body.string()).query?.pages.orEmpty()
        }
    }

    /** Spaces the requests: the API is shared, a few a second is plenty. */
    private suspend fun throttled() = gate.withLock {
        val wait = lastRequestAt + MIN_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun aboutAGame(page: Page): Boolean {
        val head = (page.description.orEmpty() + " " + page.extract.orEmpty().take(240))
        return GAME.containsMatchIn(head)
    }

    /** Keeps the first sentences that fit; Wikipedia's markup leftovers are removed. */
    private fun tidy(extract: String): String {
        val flat = extract.replace(Regex("\\s+"), " ").replace(Regex("\\s*\\[[^\\]]{1,12}\\]"), "").trim()
        if (flat.length <= MAX_TEXT) return flat
        val cut = flat.take(MAX_TEXT)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > MAX_TEXT / 2) cut.take(end + 1) else cut.trimEnd() + "…"
    }

    companion object {
        private const val MIN_TERM = 3
        private const val LONG_NAME = 8
        private const val MIN_TEXT = 60
        private const val MAX_TEXT = 700
        private const val MIN_GAP_MS = 250L
        private val GAME = Regex("(?i)video ?game|jeu vid[ée]o|computer game|jeu de |role-playing|platform game")

        /** Same name whatever the case, accents, punctuation or article. */
        fun key(text: String): String =
            Normalizer.normalize(ArtworkTitle.searchTerm(text), Normalizer.Form.NFD)
                .lowercase().filter { it.isLetterOrDigit() }
    }
}
