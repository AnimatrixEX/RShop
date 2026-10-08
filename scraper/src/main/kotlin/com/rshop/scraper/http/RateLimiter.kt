package com.rshop.scraper.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Spaces requests to the same host by at least an interval, and lets a server ask us to back off
 * (429/503 with Retry-After). Requests are serialized: the scraper never hammers a site in parallel.
 */
class RateLimiter(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private val nextAllowed = HashMap<String, ComparableTimeMark>()

    suspend fun acquire(host: String, interval: Duration) {
        mutex.withLock {
            nextAllowed[host]?.let { mark ->
                val wait = -mark.elapsedNow()
                if (wait.isPositive()) delay(wait)
            }
            nextAllowed[host] = timeSource.markNow() + interval
        }
    }

    /** Pushes the next allowed request for [host] at least [duration] from now. */
    suspend fun backOff(host: String, duration: Duration) {
        mutex.withLock {
            val candidate = timeSource.markNow() + duration
            val current = nextAllowed[host]
            if (current == null || candidate > current) nextAllowed[host] = candidate
        }
    }
}
