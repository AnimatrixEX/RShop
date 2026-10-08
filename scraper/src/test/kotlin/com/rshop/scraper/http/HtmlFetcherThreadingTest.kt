package com.rshop.scraper.http

import com.rshop.scraper.testing.FixtureSite
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression: on Android, reading the response body after resuming on the main thread crashed
 * with NetworkOnMainThreadException. The fetch must never do I/O on its caller's thread.
 */
class HtmlFetcherThreadingTest {

    private val site = FixtureSite()

    @After
    fun tearDown() = site.close()

    @Test
    fun `page is fetched off the calling thread`() {
        val callerExecutor = Executors.newSingleThreadExecutor { Thread(it, "caller-main") }
        val ioThreads = mutableSetOf<String>()
        val ioDispatcher = Executors.newSingleThreadExecutor { Thread(it, "io-pool").also { t -> ioThreads += t.name } }
            .asCoroutineDispatcher()
        val fetcher = HtmlFetcher(
            client = okhttp3.OkHttpClient(),
            userAgent = "RShop-Test",
            productToken = "RShop",
            ioDispatcher = ioDispatcher,
        )

        val title = runBlocking {
            withContext(callerExecutor.asCoroutineDispatcher()) {
                fetcher.fetch((site.baseUrl + "games").toHttpUrl(), 10.milliseconds).title()
            }
        }

        assertEquals("Homebrew Hub - Free homebrew games", title)
        assertTrue(ioThreads.contains("io-pool"))
        callerExecutor.shutdown()
        ioDispatcher.close()
    }
}
