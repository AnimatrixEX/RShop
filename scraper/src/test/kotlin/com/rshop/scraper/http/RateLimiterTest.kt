package com.rshop.scraper.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {

    @Test
    fun `requests to one host are spaced by the interval`() = runTest {
        val limiter = RateLimiter(testScheduler.timeSource)
        val times = mutableListOf<Long>()
        repeat(3) {
            limiter.acquire("a.org", 2.seconds)
            times += currentTime
        }
        assertEquals(listOf(0L, 2_000L, 4_000L), times)
    }

    @Test
    fun `hosts are limited independently`() = runTest {
        val limiter = RateLimiter(testScheduler.timeSource)
        limiter.acquire("a.org", 2.seconds)
        limiter.acquire("b.org", 2.seconds)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `back off delays the next request`() = runTest {
        val limiter = RateLimiter(testScheduler.timeSource)
        limiter.acquire("a.org", 1.seconds)
        limiter.backOff("a.org", 30.seconds)
        limiter.acquire("a.org", 1.seconds)
        assertEquals(30_000L, currentTime)
    }
}
