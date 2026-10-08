package com.rshop.download

import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/** At most [MAX_PARALLEL] transfers at once; other queued downloads wait their turn. */
@Singleton
class DownloadSlots @Inject constructor() {
    val semaphore = Semaphore(MAX_PARALLEL)

    private companion object {
        const val MAX_PARALLEL = 2
    }
}
