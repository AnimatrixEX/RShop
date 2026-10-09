package com.rshop.scraper.drive.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The OAuth client the user created in their own Google Cloud project (type "Desktop app"). */
data class OAuthClient(val id: String, val secret: String)

/** A sign-in in progress: where to send the user, and what is needed to finish it. */
data class OAuthSession(val authUrl: String, val verifier: String, val state: String, val redirectUri: String)

data class OAuthTokens(
    val accessToken: String,
    /** Epoch milliseconds after which [accessToken] must not be used. */
    val expiresAtMs: Long,
    /** Only sent the first time (and when consent is asked again); keep the stored one otherwise. */
    val refreshToken: String?,
    /** The signed-in account, read from the ID token. */
    val email: String?,
)

class OAuthException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** The user said no on Google's page. */
        Denied,

        /** The stored sign-in is no longer valid (revoked, or expired: 7 days for an app still in testing). */
        InvalidGrant,

        /** Google does not accept the client id or secret. */
        InvalidClient,
        Network,
        Other,
    }
}

/**
 * Google sign-in for an installed app: authorization code with PKCE, the redirect coming back to
 * a loopback address ([LoopbackReceiver]). Only Drive read access (and the account's e-mail, to
 * show who is signed in) is requested. Calls block: run them off the main thread.
 */
class GoogleOAuth(
    private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val authEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
    private val revokeEndpoint: String = "https://oauth2.googleapis.com/revoke",
    private val random: SecureRandom = SecureRandom(),
) {
    /** The page to open in the browser, with the secrets to keep until the code comes back. */
    fun begin(oauth: OAuthClient, redirectUri: String): OAuthSession {
        val verifier = randomToken(48)
        val state = randomToken(24)
        val url = authEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", oauth.id)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("code_challenge", challengeOf(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            // Offline access gives the refresh token; the consent screen is shown again so it is always sent.
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .build()
        return OAuthSession(url.toString(), verifier, state, redirectUri)
    }

    fun exchange(oauth: OAuthClient, session: OAuthSession, code: String): OAuthTokens = token(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", session.redirectUri)
            .add("code_verifier", session.verifier)
            .add("client_id", oauth.id)
            .add("client_secret", oauth.secret)
            .build(),
    )

    fun refresh(oauth: OAuthClient, refreshToken: String): OAuthTokens = token(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", oauth.id)
            .add("client_secret", oauth.secret)
            .build(),
    )

    /** Tells Google to forget the grant; best effort, a failure changes nothing for the user. */
    fun revoke(token: String) {
        try {
            val body = FormBody.Builder().add("token", token).build()
            client.newCall(Request.Builder().url(revokeEndpoint).post(body).build()).execute().close()
        } catch (_: IOException) {
        }
    }

    private fun token(body: FormBody): OAuthTokens {
        val response = try {
            client.newCall(Request.Builder().url(tokenEndpoint).post(body).build()).execute().use { it.code to it.body.string() }
        } catch (e: IOException) {
            throw OAuthException(OAuthException.Kind.Network, "Cannot reach Google: ${e.message}", e)
        }
        val (code, text) = response
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code != 200 || json == null || json["access_token"] == null) throw error(code, json)
        val seconds = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        return OAuthTokens(
            accessToken = json.getValue("access_token").jsonPrimitive.content,
            expiresAtMs = clock() + seconds * 1000,
            refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull,
            email = json["id_token"]?.jsonPrimitive?.contentOrNull?.let(::emailOf),
        )
    }

    private fun error(code: Int, json: JsonObject?): OAuthException {
        val error = json?.get("error")?.jsonPrimitive?.contentOrNull
        val description = json?.get("error_description")?.jsonPrimitive?.contentOrNull
        val kind = when (error) {
            "invalid_grant" -> OAuthException.Kind.InvalidGrant
            "invalid_client", "unauthorized_client" -> OAuthException.Kind.InvalidClient
            "access_denied" -> OAuthException.Kind.Denied
            else -> OAuthException.Kind.Other
        }
        return OAuthException(kind, "Google refused the sign-in (HTTP $code${error?.let { ", $it" } ?: ""}${description?.let { ": $it" } ?: ""})")
    }

    private fun randomToken(bytes: Int): String {
        val data = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    companion object {
        const val SCOPES = "openid email https://www.googleapis.com/auth/drive.readonly"

        /** PKCE S256: base64url(SHA-256(verifier)), without padding (RFC 7636). */
        fun challengeOf(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** The e-mail claim of an ID token received straight from Google over TLS (its signature is not re-checked). */
        fun emailOf(idToken: String): String? = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(idToken.split('.')[1]), Charsets.UTF_8)
            Json.parseToJsonElement(payload).jsonObject["email"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }
}
