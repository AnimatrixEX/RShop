package com.rshop.scraper.drive

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveAuthInterceptorTest {

    @Test
    fun `a refused access token is dropped and the download asked again once with a fresh one`() {
        MockWebServer().use { server ->
            val seen = mutableListOf<String?>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val auth = request.headers["Authorization"]
                    seen += auth
                    return if (auth == "Bearer fresh") MockResponse.Builder().body("bytes").build() else MockResponse.Builder().code(401).build()
                }
            }
            server.start()
            var token = "old"
            val rejected = mutableListOf<String>()
            val client = OkHttpClient.Builder()
                .addInterceptor(
                    DriveAuthInterceptor(
                        credentials = { DriveCredentials(apiKey = "key", bearerToken = token) },
                        appliesTo = { true },
                        onTokenRejected = { rejected += it; token = "fresh" },
                    ),
                )
                .build()

            val url = server.url("/drive/v3/files/abc?alt=media")
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf("old"), rejected)
            assertEquals(listOf("Bearer old", "Bearer fresh"), seen)

            // A token refused again is not retried endlessly.
            token = "bad"
            val stubborn = OkHttpClient.Builder()
                .addInterceptor(DriveAuthInterceptor({ DriveCredentials(bearerToken = "bad") }, appliesTo = { true }))
                .build()
            seen.clear()
            stubborn.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(401, it.code) }
            assertEquals(listOf("Bearer bad"), seen)
        }
    }
}
