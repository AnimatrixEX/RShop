package com.rshop.scraper.http

import com.rshop.scraper.ScraperException
import com.rshop.scraper.ScraperLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Polite HTML client: checks robots.txt, rate-limits per host, retries transient failures with
 * backoff, honors Retry-After, and refuses non-HTML or oversized responses.
 * 401/403 are reported as [ScraperException.AccessDenied] and never retried or worked around.
 */
class HtmlFetcher(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val productToken: String,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val log: ScraperLog = ScraperLog.None,
    private val maxAttempts: Int = 3,
    private val maxBodyBytes: Long = 8L * 1024 * 1024,
    private val initialBackoff: Duration = 2.seconds,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxRedirects: Int = 10,
    /** How long a fetched page is reused instead of asking the site again. Zero disables the cache. */
    private val cacheTtl: Duration = 5.minutes,
) {
    private class CachedRobots(val robots: RobotsTxt, val expires: ComparableTimeMark)

    private val robotsMutex = Mutex()
    private val robotsCache = HashMap<String, CachedRobots>()
    private val pageCache = PageCache(timeSource, cacheTtl)

    /**
     * Fetches and parses a page. Always runs on [ioDispatcher], whoever calls it: response bodies
     * are read with blocking I/O, which must never happen on the caller's (possibly main) thread.
     * Throws [ScraperException.NotHtml] when the URL serves a file.
     */
    suspend fun fetch(url: HttpUrl, minInterval: Duration, useCache: Boolean = true): Document =
        when (val result = open(url, minInterval, useCache)) {
            is FetchResult.Page -> result.document
            is FetchResult.File -> throw ScraperException.NotHtml(url.toString(), result.file.finalUrl, result.file.contentType)
        }

    /**
     * One request that tells a page from a file. For a file only the headers are read (type, size,
     * name, final URL after redirects): the body is never downloaded here.
     */
    suspend fun open(url: HttpUrl, minInterval: Duration, useCache: Boolean = true, referer: HttpUrl? = null): FetchResult =
        withContext(ioDispatcher) {
            if (useCache) pageCache.get(url.toString())?.let { return@withContext FetchResult.Page(it) }
            openOnIo(url, minInterval, referer).also { result ->
                if (useCache && result is FetchResult.Page) pageCache.put(url.toString(), result.document)
            }
        }

    private suspend fun openOnIo(url: HttpUrl, minInterval: Duration, referer: HttpUrl?): FetchResult {
        val robots = robotsFor(url, minInterval)
        if (!robots.isAllowed(pathAndQuery(url))) throw ScraperException.BlockedByRobots(url.toString())
        val interval = maxOf(minInterval, robots.crawlDelay ?: Duration.ZERO)

        var backoff = initialBackoff
        var lastError: ScraperException? = null
        repeat(maxAttempts) { attempt ->
            rateLimiter.acquire(url.host, interval)
            try {
                execute(request(url, referer)).use { response ->
                    when {
                        response.isSuccessful -> return read(url, response)
                        (response.code == 403 || response.code == 503) && Challenge.isChallengeResponse(response) ->
                            throw ScraperException.Captcha(url.toString())
                        response.code == 401 || response.code == 403 ->
                            throw ScraperException.AccessDenied(url.toString(), response.code)
                        response.code == 429 || response.code == 503 -> {
                            val wait = retryAfter(response) ?: backoff
                            log.warn("HTTP ${response.code} for $url, backing off $wait")
                            rateLimiter.backOff(url.host, wait)
                            lastError = ScraperException.Busy(url.toString())
                        }
                        response.code >= 500 -> {
                            log.warn("HTTP ${response.code} for $url (attempt ${attempt + 1})")
                            lastError = ScraperException.Http(url.toString(), response.code)
                            delay(backoff)
                        }
                        else -> throw ScraperException.Http(url.toString(), response.code)
                    }
                }
            } catch (e: IOException) {
                log.warn("Network error for $url (attempt ${attempt + 1}): ${e.message}")
                lastError = ScraperException.Network(url.toString(), e)
                delay(backoff)
            }
            backoff *= 2
        }
        throw lastError ?: ScraperException.Network(url.toString(), IOException("No attempt made"))
    }

    private fun read(url: HttpUrl, response: Response): FetchResult {
        val redirects = generateSequence(response.priorResponse) { it.priorResponse }.count()
        if (redirects > maxRedirects) throw ScraperException.InvalidContent(url.toString(), "more than $maxRedirects redirects")
        val body = response.body
        val type = body.contentType()
        val disposition = ContentDisposition.parse(response.header("Content-Disposition"))
        if ((type != null && type.subtype !in HTML_SUBTYPES) || disposition.attachment) {
            val finalUrl = response.request.url
            return FetchResult.File(
                RemoteFile(
                    url = url.toString(),
                    finalUrl = finalUrl.toString(),
                    fileName = disposition.fileName ?: finalUrl.pathSegments.lastOrNull { it.isNotBlank() },
                    sizeBytes = body.contentLength().takeIf { it >= 0 },
                    contentType = type?.let { "${it.type}/${it.subtype}" },
                ),
            )
        }
        if (body.contentLength() > maxBodyBytes) {
            throw ScraperException.InvalidContent(url.toString(), "page larger than $maxBodyBytes bytes")
        }
        val bytes = body.source().use { source ->
            if (source.request(maxBodyBytes + 1)) {
                throw ScraperException.InvalidContent(url.toString(), "page larger than $maxBodyBytes bytes")
            }
            source.readByteArray()
        }
        val charset = type?.charset() ?: Charsets.UTF_8
        // The final URL (after redirects) is the base for resolving relative links.
        val document = Jsoup.parse(String(bytes, charset), response.request.url.toString())
        // An interstitial challenge replaces the whole page: report it, never try to pass it.
        if (Challenge.isInterstitial(document)) throw ScraperException.Captcha(url.toString())
        return FetchResult.Page(document)
    }

    private suspend fun robotsFor(url: HttpUrl, minInterval: Duration): RobotsTxt {
        val origin = "${url.scheme}://${url.host}:${url.port}"
        return robotsMutex.withLock {
            robotsCache[origin]?.takeIf { it.expires.hasNotPassedNow() }?.let { return@withLock it.robots }
            val robotsUrl = url.newBuilder().encodedPath("/robots.txt").query(null).fragment(null).build()
            rateLimiter.acquire(url.host, minInterval)
            val (robots, ttl) = try {
                execute(request(robotsUrl)).use { response ->
                    when {
                        response.isSuccessful -> RobotsTxt.parse(response.body.string(), productToken) to 24.hours
                        // RFC 9309: 4xx means "unavailable", crawling is allowed.
                        response.code in 400..499 -> RobotsTxt.ALLOW_ALL to 24.hours
                        // 5xx means "unreachable": assume everything is disallowed, retry soon.
                        else -> RobotsTxt.DISALLOW_ALL to 5.minutes
                    }
                }
            } catch (e: IOException) {
                log.warn("robots.txt unreachable for $origin: ${e.message}")
                RobotsTxt.DISALLOW_ALL to 5.minutes
            }
            robotsCache[origin] = CachedRobots(robots, timeSource.markNow() + ttl)
            robots
        }
    }

    private fun request(url: HttpUrl, referer: HttpUrl? = null) = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .apply { if (referer != null) header("Referer", referer.toString()) }
        .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
        .build()

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, _, _ -> response.close() }
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        })
    }

    private fun retryAfter(response: Response): Duration? {
        val header = response.header("Retry-After") ?: return null
        header.toLongOrNull()?.let { return it.coerceIn(1, 3_600).seconds }
        return try {
            val date = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            (date.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(1_000, 3_600_000).milliseconds
        } catch (_: Exception) {
            null
        }
    }

    private fun pathAndQuery(url: HttpUrl) =
        url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

    private companion object {
        val HTML_SUBTYPES = setOf("html", "xhtml+xml", "xml")
    }
}
