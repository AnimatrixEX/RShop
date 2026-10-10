package com.rshop.scraper.drive

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * How requests to Drive are authorised: the Google account the user signed in with ([bearerToken],
 * for private and shared-with-me folders) or, for a folder anyone with the link can read, the API
 * key of the user's own Google Cloud project. [androidPackage] and [androidCert] (SHA-1 of the
 * signing certificate, hex) go with the key so that a key restricted to this Android app is accepted.
 */
data class DriveCredentials(
    val apiKey: String? = null,
    val androidPackage: String? = null,
    val androidCert: String? = null,
    val bearerToken: String? = null,
)

/**
 * Authorises Drive API requests, and only them. The credentials never appear in a stored URL
 * (downloads keep a link without them; they are added at the moment of the request), and a resource
 * key carried by [RESOURCE_KEY_PARAM] becomes the header Drive expects.
 */
class DriveAuthInterceptor(
    private val credentials: () -> DriveCredentials?,
    private val appliesTo: (HttpUrl) -> Boolean = ::isDriveApi,
) : Interceptor {

    /**
     * With both a key and an account: listings go with the key, exactly as without an account,
     * because a signed-in account only lists what is in its own Drive (a folder shared by link and
     * never opened lists as empty); downloads go with the account, whose download quota is its own.
     * To read a private folder, the user signs in without a key.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!appliesTo(request.url)) return chain.proceed(request)
        val creds = credentials()
        val media = request.url.queryParameter("alt") == "media"
        val withAccount = creds?.bearerToken != null && (media || creds.apiKey == null)
        return chain.proceed(authorize(request, creds, withAccount))
    }

    private fun authorize(request: Request, creds: DriveCredentials?, withAccount: Boolean): Request {
        val resourceKey = request.url.queryParameter(RESOURCE_KEY_PARAM)
        val builder = request.newBuilder()
        val url = request.url.newBuilder().removeAllQueryParameters(RESOURCE_KEY_PARAM).apply {
            if (!withAccount) creds?.apiKey?.let { setQueryParameter("key", it) }
        }.build()
        builder.url(url)
        when {
            withAccount -> builder.header("Authorization", "Bearer ${creds?.bearerToken}")
            creds != null -> {
                creds.androidPackage?.let { builder.header("X-Android-Package", it) }
                creds.androidCert?.let { builder.header("X-Android-Cert", it) }
            }
        }
        if (resourceKey != null && request.header(RESOURCE_KEYS_HEADER) == null) {
            val fileId = request.url.pathSegments.getOrNull(3)
            if (fileId != null) builder.header(RESOURCE_KEYS_HEADER, "$fileId/$resourceKey")
        }
        return builder.build()
    }

    companion object {
        const val RESOURCE_KEY_PARAM = "rshop_rk"
        const val RESOURCE_KEYS_HEADER = "X-Goog-Drive-Resource-Keys"
        const val API_HOST = "www.googleapis.com"

        fun isDriveApi(url: HttpUrl): Boolean =
            url.scheme == "https" && url.host == API_HOST && url.encodedPath.startsWith("/drive/v3/")
    }
}
