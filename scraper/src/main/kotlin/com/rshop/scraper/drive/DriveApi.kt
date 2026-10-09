package com.rshop.scraper.drive

import com.rshop.scraper.ScraperException
import com.rshop.scraper.ScraperLog
import com.rshop.scraper.http.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    /** Bytes, as a string (Drive sends 64-bit numbers that way). Absent for folders. */
    val size: String? = null,
    val modifiedTime: String? = null,
    val md5Checksum: String? = null,
    val resourceKey: String? = null,
    val parents: List<String> = emptyList(),
    val shortcutDetails: ShortcutDetails? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
    val isShortcut: Boolean get() = mimeType == SHORTCUT_MIME

    /** Google Docs, Sheets… have no bytes to download. */
    val isGoogleDocument: Boolean get() = mimeType.startsWith("application/vnd.google-apps.") && !isFolder && !isShortcut
    val sizeBytes: Long? get() = size?.toLongOrNull()

    /** A shortcut stands for its target; everything else is itself. */
    fun resolved(): DriveFile {
        val target = shortcutDetails?.takeIf { isShortcut && it.targetId != null && it.targetMimeType != null } ?: return this
        return copy(id = target.targetId!!, mimeType = target.targetMimeType!!, resourceKey = target.targetResourceKey, shortcutDetails = null)
    }

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
    }
}

@Serializable
data class ShortcutDetails(
    val targetId: String? = null,
    val targetMimeType: String? = null,
    val targetResourceKey: String? = null,
)

@Serializable
private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

/**
 * Reads folders and file metadata through the Drive API v3 with the user's API key (added by
 * [DriveAuthInterceptor] on [client]). Requests are spaced by the shared [RateLimiter]; rate-limit
 * answers are waited out a few times and then reported. A spent download quota is reported, never
 * worked around.
 */
class DriveApi(
    private val client: OkHttpClient,
    private val rateLimiter: RateLimiter,
    private val credentials: suspend () -> DriveCredentials?,
    private val interval: Duration,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/drive/v3/".toHttpUrl(),
    private val log: ScraperLog = ScraperLog.None,
    private val backoff: Duration = 2.seconds,
) {
    /** True for a media or listing URL of this API. */
    fun isApiUrl(url: HttpUrl): Boolean = url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port

    /**
     * Link that serves the bytes of a file. It holds no API key (added when the request is made); a
     * resource key rides along as a private parameter the interceptor turns into a header.
     */
    fun mediaUrl(fileId: String, resourceKey: String? = null): HttpUrl =
        baseUrl.newBuilder().addPathSegment("files").addPathSegment(fileId).addQueryParameter("alt", "media").apply {
            if (resourceKey != null) addQueryParameter(DriveAuthInterceptor.RESOURCE_KEY_PARAM, resourceKey)
        }.build()

    /** The file or folder [fileId]. */
    suspend fun getFile(fileId: String, resourceKey: String? = null): DriveFile {
        val url = baseUrl.newBuilder().addPathSegment("files").addPathSegment(fileId)
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        val body = get(url, resourceKeyHeader(listOf(fileId to resourceKey)))
        return JSON.decodeFromString(DriveFile.serializer(), body)
    }

    /**
     * The direct children (folders and files, not trashed) of every folder in [folders], grouped by
     * parent. One request covers up to [BATCH] folders; pages are followed to the end.
     */
    suspend fun listChildren(folders: List<DriveFile>): Map<String, List<DriveFile>> {
        val result = LinkedHashMap<String, MutableList<DriveFile>>()
        folders.forEach { result[it.id] = mutableListOf() }
        for (batch in folders.chunked(BATCH)) {
            val query = batch.joinToString(" or ", prefix = "(", postfix = ") and trashed = false") { "'${it.id}' in parents" }
            val keys = resourceKeyHeader(batch.map { it.id to it.resourceKey })
            var token: String? = null
            do {
                val url = baseUrl.newBuilder().addPathSegment("files")
                    .addQueryParameter("q", query)
                    .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                    .addQueryParameter("pageSize", "1000")
                    .addQueryParameter("orderBy", "name")
                    .addQueryParameter("supportsAllDrives", "true")
                    .addQueryParameter("includeItemsFromAllDrives", "true")
                    .apply { token?.let { addQueryParameter("pageToken", it) } }
                    .build()
                val page = JSON.decodeFromString(FileList.serializer(), get(url, keys))
                for (file in page.files) {
                    for (parent in file.parents) result[parent]?.add(file)
                }
                token = page.nextPageToken
            } while (token != null)
        }
        return result
    }

    private fun resourceKeyHeader(pairs: List<Pair<String, String?>>): String? =
        pairs.filter { it.second != null }.joinToString(",") { "${it.first}/${it.second}" }.ifEmpty { null }

    private suspend fun get(url: HttpUrl, resourceKeys: String?): String {
        if (credentials() == null) throw ScraperException.ApiKeyMissing(SERVICE)
        var attempt = 0
        while (true) {
            rateLimiter.acquire(url.host, interval)
            val request = Request.Builder().url(url).header("Accept", "application/json").apply {
                resourceKeys?.let { header(DriveAuthInterceptor.RESOURCE_KEYS_HEADER, it) }
            }.build()
            val outcome = try {
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { it.code to it.body.string() to it.header("Retry-After") }
                }
            } catch (e: IOException) {
                if (++attempt < MAX_ATTEMPTS) {
                    log.warn("Drive request failed, retrying: ${e.message}")
                    delay(backoff * attempt)
                    continue
                }
                throw ScraperException.Network(url.redactedString(), e)
            }
            val (codeAndBody, retryAfter) = outcome
            val (code, body) = codeAndBody
            if (code == 200) return body
            val error = DriveError.parse(code, body)
            val retryable = error.kind == DriveError.Kind.RateLimited || code in 500..599
            if (retryable && ++attempt < MAX_ATTEMPTS) {
                val wait = retryAfter?.toLongOrNull()?.seconds ?: (backoff * attempt)
                log.warn("Drive answered HTTP $code (${error.reason}), waiting $wait")
                rateLimiter.backOff(url.host, wait)
                delay(wait)
                continue
            }
            throw error.toException(url.redactedString())
        }
    }

    private fun HttpUrl.redactedString(): String = newBuilder().removeAllQueryParameters("key").build().toString()

    companion object {
        const val SERVICE = "Google Drive"
        const val BATCH = 20
        private const val MAX_ATTEMPTS = 4
        private const val FILE_FIELDS =
            "id,name,mimeType,size,modifiedTime,md5Checksum,resourceKey,parents,shortcutDetails(targetId,targetMimeType,targetResourceKey)"

        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** What the Drive API said went wrong, in terms of what the app can do about it. */
internal class DriveError(val code: Int, val kind: Kind, val reason: String?, val message: String?) {
    enum class Kind { RateLimited, Quota, KeyRejected, Forbidden, NotFound, Other }

    fun toException(url: String): ScraperException = when (kind) {
        Kind.RateLimited -> ScraperException.Busy(url)
        Kind.Quota -> ScraperException.QuotaExceeded(url, reason)
        Kind.KeyRejected -> ScraperException.ApiKeyRejected(DriveApi.SERVICE, reason ?: message)
        // Google's own words say why (folder not shared with the link, domain policy…).
        Kind.Forbidden -> ScraperException.AccessDenied(url, code, message ?: reason)
        Kind.NotFound -> ScraperException.Http(url, 404)
        Kind.Other -> ScraperException.Http(url, code)
    }

    companion object {
        private val RATE = setOf("rateLimitExceeded", "userRateLimitExceeded")
        private val QUOTA = setOf("downloadQuotaExceeded", "dailyLimitExceeded", "quotaExceeded", "sharingRateLimitExceeded")
        private val KEY = setOf(
            "keyInvalid", "API_KEY_INVALID", "API_KEY_ANDROID_APP_BLOCKED", "API_KEY_HTTP_REFERRER_BLOCKED",
            "API_KEY_IP_ADDRESS_BLOCKED", "API_KEY_SERVICE_BLOCKED", "accessNotConfigured", "SERVICE_DISABLED", "ipRefererBlocked",
            // Drive does not see a valid key at all.
            "dailyLimitExceededUnreg", "keyExpired",
        )

        fun parse(code: Int, body: String): DriveError {
            val reasons = mutableListOf<String>()
            var message: String? = null
            runCatching {
                val error = DriveApi.JSON.parseToJsonElement(body).jsonObject["error"] as? JsonObject
                message = error?.get("message")?.jsonPrimitive?.contentOrNull
                error?.get("errors")?.jsonArray?.forEach { e ->
                    e.jsonObject["reason"]?.jsonPrimitive?.contentOrNull?.let(reasons::add)
                }
                error?.get("details")?.jsonArray?.forEach { d ->
                    d.jsonObject["reason"]?.jsonPrimitive?.contentOrNull?.let(reasons::add)
                }
            }
            val reason = reasons.firstOrNull()
            val kind = when {
                reasons.any { it in KEY } || (code == 400 && message?.contains("API key", ignoreCase = true) == true) -> Kind.KeyRejected
                reasons.any { it in RATE } || code == 429 -> Kind.RateLimited
                reasons.any { it in QUOTA } -> Kind.Quota
                code == 401 || code == 403 -> Kind.Forbidden
                code == 404 -> Kind.NotFound
                else -> Kind.Other
            }
            return DriveError(code, kind, reasons.firstOrNull { it in KEY || it in QUOTA } ?: reason, message)
        }
    }
}
