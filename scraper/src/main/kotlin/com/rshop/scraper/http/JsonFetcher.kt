package com.rshop.scraper.http

import com.rshop.scraper.ScraperException
import com.rshop.scraper.ScraperLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polite client for a JSON API: rate limit per host, retries with backoff for transient failures,
 * Retry-After honored, bounded response size. 401/403 are reported as
 * [ScraperException.AccessDenied] and never retried. robots.txt is not consulted: it governs
 * crawlers of web pages, while an API endpoint is the owner's explicit door for apps.
 */
class JsonFetcher(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val log: ScraperLog = ScraperLog.None,
    private val maxAttempts: Int = 3,
    private val maxBodyBytes: Long = 16L * 1024 * 1024,
    private val initialBackoff: Duration = 2.seconds,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Always runs on [ioDispatcher]: response bodies are read with blocking I/O. */
    suspend fun fetch(url: HttpUrl, minInterval: Duration): JsonElement = withContext(ioDispatcher) {
        var backoff = initialBackoff
        var lastError: ScraperException? = null
        repeat(maxAttempts) { attempt ->
            rateLimiter.acquire(url.host, minInterval)
            try {
                execute(request(url)).use { response ->
                    when {
                        response.isSuccessful -> return@withContext parse(url, response)
                        (response.code == 403 || response.code == 503) && Challenge.isChallengeResponse(response) ->
                            throw ScraperException.Captcha(url.toString())
                        response.code == 401 || response.code == 403 -> throw ScraperException.AccessDenied(url.toString(), response.code)
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

    private fun parse(url: HttpUrl, response: Response): JsonElement {
        val body = response.body
        if (body.contentLength() > maxBodyBytes) throw ScraperException.InvalidContent(url.toString(), "response larger than $maxBodyBytes bytes")
        val bytes = body.source().use { source ->
            if (source.request(maxBodyBytes + 1)) throw ScraperException.InvalidContent(url.toString(), "response larger than $maxBodyBytes bytes")
            source.readByteArray()
        }
        val charset = body.contentType()?.charset() ?: Charsets.UTF_8
        try {
            return Json.parseToJsonElement(String(bytes, charset))
        } catch (e: SerializationException) {
            throw ScraperException.InvalidContent(url.toString(), "the answer is not JSON (${body.contentType() ?: "no content type"})")
        } catch (e: IllegalArgumentException) {
            throw ScraperException.InvalidContent(url.toString(), "the answer is not JSON (${body.contentType() ?: "no content type"})")
        }
    }

    private fun request(url: HttpUrl) = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
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
}
