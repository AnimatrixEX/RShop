package com.rshop.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class LiveProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
    val installedBytes: Long? = null,
)

/**
 * In-memory progress of running downloads (the worker runs in the app process). The database only
 * gets a write about once per second; speed and ETA are computed here over a sliding window.
 */
@Singleton
class DownloadProgressTracker @Inject constructor() {
    private val _live = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<String, LiveProgress>> = _live.asStateFlow()

    private val samples = HashMap<String, ArrayDeque<Pair<Long, Long>>>()

    @Synchronized
    fun onDownload(gameId: String, downloaded: Long, total: Long?, nowMs: Long = System.currentTimeMillis()) {
        val window = samples.getOrPut(gameId) { ArrayDeque() }
        window.addLast(nowMs to downloaded)
        while (window.size > 2 && nowMs - window.first().first > WINDOW_MS) window.removeFirst()
        val (startMs, startBytes) = window.first()
        val elapsed = nowMs - startMs
        val speed = if (elapsed >= MIN_ELAPSED_MS) (downloaded - startBytes) * 1000 / elapsed else null
        _live.update { it + (gameId to LiveProgress(downloaded, total, speed)) }
    }

    @Synchronized
    fun onInstall(gameId: String, installed: Long) {
        _live.update { map ->
            val current = map[gameId] ?: LiveProgress(0, null, null)
            map + (gameId to current.copy(bytesPerSecond = null, installedBytes = installed))
        }
    }

    @Synchronized
    fun clear(gameId: String) {
        samples.remove(gameId)
        _live.update { it - gameId }
    }

    private companion object {
        const val WINDOW_MS = 5_000L
        const val MIN_ELAPSED_MS = 500L
    }
}
