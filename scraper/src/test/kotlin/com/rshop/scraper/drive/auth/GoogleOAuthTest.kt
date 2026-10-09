package com.rshop.scraper.drive.auth

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GoogleOAuthTest {

    private val server = MockWebServer().apply { start() }
    private val oauth = OAuthClient("client-id.apps.googleusercontent.com", "client-secret")

    private fun google(now: Long = 1_000_000) = GoogleOAuth(
        client = OkHttpClient(),
        clock = { now },
        tokenEndpoint = server.url("/token").toString(),
        revokeEndpoint = server.url("/revoke").toString(),
    )

    @After
    fun tearDown() = server.close()

    private fun idToken(email: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"RS256"}""", """{"email":"$email","sub":"1"}""", "signature")
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }

    @Test
    fun `PKCE challenge matches the RFC 7636 example`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GoogleOAuth.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `the sign-in page asks for read-only Drive access with PKCE and offline access`() {
        val session = google().begin(oauth, "http://127.0.0.1:5555")
        val url = session.authUrl.toHttpUrl()
        assertEquals("accounts.google.com", url.host)
        assertEquals(oauth.id, url.queryParameter("client_id"))
        assertEquals("http://127.0.0.1:5555", url.queryParameter("redirect_uri"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(GoogleOAuth.challengeOf(session.verifier), url.queryParameter("code_challenge"))
        assertEquals("offline", url.queryParameter("access_type"))
        assertEquals(session.state, url.queryParameter("state"))
        val scopes = url.queryParameter("scope")!!.split(' ')
        assertTrue("https://www.googleapis.com/auth/drive.readonly" in scopes)
        // Nothing that can write or delete.
        assertFalse(scopes.any { it.endsWith("/auth/drive") || it.endsWith("drive.file") })
    }

    @Test
    fun `the code is exchanged with the verifier and the tokens are read`() {
        server.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "application/json")
                .body("""{"access_token":"at-1","expires_in":3600,"refresh_token":"rt-1","id_token":"${idToken("me@example.com")}"}""").build(),
        )
        val oauthApi = google(now = 5_000)
        val session = oauthApi.begin(oauth, "http://127.0.0.1:5555")

        val tokens = oauthApi.exchange(oauth, session, "the-code")

        assertEquals(OAuthTokens("at-1", 5_000 + 3_600_000, "rt-1", "me@example.com"), tokens)
        val form = server.takeRequest().body!!.utf8()
        assertTrue("grant_type=authorization_code" in form)
        assertTrue("code=the-code" in form)
        assertTrue("code_verifier=${session.verifier}" in form)
        assertTrue("client_secret=client-secret" in form)
    }

    @Test
    fun `a refresh returns a new access token and no new refresh token`() {
        server.enqueue(MockResponse.Builder().body("""{"access_token":"at-2","expires_in":1800}""").build())
        val tokens = google(now = 0).refresh(oauth, "rt-1")
        assertEquals("at-2", tokens.accessToken)
        assertNull(tokens.refreshToken)
        assertEquals(1_800_000, tokens.expiresAtMs)
        assertTrue("grant_type=refresh_token" in server.takeRequest().body!!.utf8())
    }

    @Test
    fun `Google's refusals are told apart`() {
        fun fails(json: String, kind: OAuthException.Kind) {
            server.enqueue(MockResponse.Builder().code(400).body(json).build())
            try {
                google().refresh(oauth, "rt")
                fail("expected $kind")
            } catch (e: OAuthException) {
                assertEquals(kind, e.kind)
            }
        }
        fails("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""", OAuthException.Kind.InvalidGrant)
        fails("""{"error":"invalid_client"}""", OAuthException.Kind.InvalidClient)
        fails("""{"error":"access_denied"}""", OAuthException.Kind.Denied)
        fails("not json", OAuthException.Kind.Other)
    }

    @Test
    fun `the loopback receiver accepts the matching state`() {
        LoopbackReceiver().use { receiver ->
            val pool = Executors.newSingleThreadExecutor()
            val waiting = pool.submit<LoopbackReceiver.Result> { receiver.await("state-1", 10_000) }
            val http = OkHttpClient()
            http.newCall(Request.Builder().url(receiver.redirectUri + "/favicon.ico").build()).execute().use { assertEquals(404, it.code) }
            http.newCall(Request.Builder().url(receiver.redirectUri + "/?code=abc&state=state-1").build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body.string().contains("RShop"))
            }
            assertEquals(LoopbackReceiver.Result.Code("abc"), waiting.get(10, TimeUnit.SECONDS))
            pool.shutdown()
        }
    }

    @Test
    fun `an answer with the wrong state or an error is a failure`() {
        for ((query, expected) in listOf(
            "/?code=abc&state=other" to "state_mismatch",
            "/?error=access_denied&state=state-1" to "access_denied",
        )) {
            LoopbackReceiver().use { receiver ->
                val pool = Executors.newSingleThreadExecutor()
                val waiting = pool.submit<LoopbackReceiver.Result> { receiver.await("state-1", 10_000) }
                OkHttpClient().newCall(Request.Builder().url(receiver.redirectUri + query).build()).execute().close()
                assertEquals(LoopbackReceiver.Result.Failure(expected), waiting.get(10, TimeUnit.SECONDS))
                pool.shutdown()
            }
        }
    }

    @Test
    fun `waiting stops at the timeout`() {
        LoopbackReceiver().use { receiver ->
            try {
                receiver.await("s", 600)
                fail("expected a timeout")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("timeout"))
            }
        }
    }
}
