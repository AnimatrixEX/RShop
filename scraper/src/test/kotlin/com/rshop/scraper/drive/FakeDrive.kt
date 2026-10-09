package com.rshop.scraper.drive

import com.rshop.scraper.http.RateLimiter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/** An in-memory Drive tree. */
class TreeBuilder {
    val files = mutableListOf<DriveFile>()
    private var next = 0

    fun folder(name: String, parent: String?, body: TreeBuilder.(String) -> Unit = {}): String {
        val id = "fold${"%06d".format(next++)}xx"
        files += DriveFile(id, name, DriveFile.FOLDER_MIME, parents = listOfNotNull(parent))
        body(id)
        return id
    }

    fun file(name: String, parent: String, size: Long = 100): String {
        val id = "file${"%06d".format(next++)}xx"
        files += DriveFile(id, name, "application/octet-stream", size = size.toString(), modifiedTime = "2026-01-02T03:04:05Z", parents = listOf(parent))
        return id
    }

    fun doc(name: String, parent: String) {
        files += DriveFile("doc${"%06d".format(next++)}xxx", name, "application/vnd.google-apps.document", parents = listOf(parent))
    }

    fun childrenOf(ids: List<String>): Map<String, List<DriveFile>> =
        ids.associateWith { id -> files.filter { id in it.parents }.sortedBy { it.name } }
}

fun tree(rootName: String = "Shared", body: TreeBuilder.(String) -> Unit): Pair<TreeBuilder, String> {
    val builder = TreeBuilder()
    val root = builder.folder(rootName, null) { body(it) }
    return builder to root
}

/**
 * Serves a [TreeBuilder] the way the Drive API v3 does: `files?q='a' in parents or …` and
 * `files/{id}`. [pageLimit] splits listings into pages to exercise `nextPageToken`.
 */
class FakeDrive(val tree: TreeBuilder, var pageLimit: Int = 1000) : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Folders that refuse to be listed (not shared with the link). */
    val refused = mutableSetOf<String>()

    /** Set to answer every request with this error (status, reason). */
    @Volatile var failure: Pair<Int, String>? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                failure?.let { (code, reason) -> return error(code, reason) }
                val url = request.url
                val segments = url.pathSegments
                if (url.queryParameter("key") != KEY) return error(400, "keyInvalid")
                return when {
                    segments == listOf("drive", "v3", "files") -> list(url.queryParameter("q").orEmpty(), url.queryParameter("pageToken"))
                    segments.size == 4 && segments[2] == "files" -> {
                        val file = tree.files.firstOrNull { it.id == segments[3] } ?: return error(404, "notFound")
                        if (url.queryParameter("alt") == "media") MockResponse.Builder().body("bytes-of-${file.name}").build() else json(fileJson(file))
                    }
                    else -> error(404, "notFound")
                }
            }
        }
        server.start()
    }

    private fun list(q: String, token: String?): MockResponse {
        val parents = Regex("'([^']+)' in parents").findAll(q).map { it.groupValues[1] }.toSet()
        if (parents.any { it in refused }) return error(403, "insufficientFilePermissions")
        val all = tree.files.filter { f -> f.parents.any { it in parents } }.sortedBy { it.name }
        val start = token?.toInt() ?: 0
        val page = all.drop(start).take(pageLimit)
        val body = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("files" to JsonArray(page.map(::fileJson)))
        if (start + pageLimit < all.size) body["nextPageToken"] = JsonPrimitive((start + pageLimit).toString())
        return json(JsonObject(body))
    }

    private fun fileJson(f: DriveFile) = JsonObject(
        buildMap {
            put("id", JsonPrimitive(f.id))
            put("name", JsonPrimitive(f.name))
            put("mimeType", JsonPrimitive(f.mimeType))
            f.size?.let { put("size", JsonPrimitive(it)) }
            f.modifiedTime?.let { put("modifiedTime", JsonPrimitive(it)) }
            put("parents", JsonArray(f.parents.map(::JsonPrimitive)))
        },
    )

    private fun json(body: JsonObject) = MockResponse.Builder().setHeader("Content-Type", "application/json").body(body.toString()).build()

    private fun error(code: Int, reason: String) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json")
        .body("""{"error":{"code":$code,"message":"failure $reason","errors":[{"reason":"$reason"}]}}""").build()

    val apiBase get() = server.url("/drive/v3/")

    fun api(key: String? = KEY): DriveApi {
        val creds = key?.let { DriveCredentials(it, "com.rshop", "AB12") }
        val client = OkHttpClient.Builder()
            .addInterceptor(DriveAuthInterceptor({ creds }, appliesTo = { it.encodedPath.startsWith("/drive/v3/") }))
            .build()
        return DriveApi(client, RateLimiter(), { creds }, 1.milliseconds, baseUrl = apiBase, backoff = 1.milliseconds)
    }

    override fun close() = server.close()

    companion object {
        const val KEY = "test-key"
    }
}
