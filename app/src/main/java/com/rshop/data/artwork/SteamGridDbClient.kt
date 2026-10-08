package com.rshop.data.artwork

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed class ArtworkException(message: String) : IOException(message) {
    /** HTTP 401: the API key is missing, wrong or revoked. */
    class InvalidKey : ArtworkException("SteamGridDB rejected the API key")
    class Http(val code: Int) : ArtworkException("SteamGridDB answered HTTP $code")
}

data class SgdbGame(val id: Long, val name: String, val verified: Boolean)

/**
 * Minimal client for the official SteamGridDB API v2 (https://www.steamgriddb.com/api/v2),
 * authenticated with the user's own API key.
 */
class SteamGridDbClient(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) {

    suspend fun search(term: String): List<SgdbGame> {
        val url = baseUrl.newBuilder().addPathSegments("search/autocomplete").addPathSegment(term).build()
        val data = get(url) ?: return emptyList()
        return data.mapNotNull { element ->
            // The documented sample nests each game in {"data": {...}}; the live API does not.
            val obj = element.jsonObject.let { (it["data"] as? JsonObject) ?: it }
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            SgdbGame(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                verified = obj["verified"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }
    }

    /** Best-voted portrait grid (box art shape), else any grid; null when the game has none. */
    suspend fun cover(gameId: Long): String? {
        val grids = baseUrl.newBuilder().addPathSegments("grids/game").addPathSegment(gameId.toString())
        val portrait = grids.build().newBuilder()
            .addQueryParameter("dimensions", PORTRAIT_DIMENSIONS)
            .addQueryParameter("types", "static")
            .addQueryParameter("limit", "10")
            .build()
        return bestUrl(get(portrait)) ?: bestUrl(get(grids.addQueryParameter("types", "static").addQueryParameter("limit", "10").build()))
    }

    private fun bestUrl(data: JsonArray?): String? = data
        ?.map { it.jsonObject }
        ?.maxByOrNull { it["score"]?.jsonPrimitive?.intOrNull ?: 0 }
        ?.get("url")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.startsWith("https://") }

    /** The `data` array of a successful answer; null for 404 (unknown game). */
    private suspend fun get(url: HttpUrl): JsonArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> throw ArtworkException.InvalidKey()
                response.code == 404 -> null
                !response.isSuccessful -> throw ArtworkException.Http(response.code)
                else -> {
                    val body: JsonElement = JSON.parseToJsonElement(response.body.string())
                    (body.jsonObject["data"] as? JsonArray) ?: body.jsonObject["data"]?.jsonArray
                }
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.steamgriddb.com/api/v2/"
        /** Portrait grid sizes, the shape of a box cover. */
        private const val PORTRAIT_DIMENSIONS = "600x900,342x482,660x930"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
