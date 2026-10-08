package com.rshop.download

import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files the in-app browser is handing over: the live response body, read by [DownloadWorker] so
 * the transfer runs in the foreground service and survives the browser screen being closed.
 * [release] lets the browser close the page session that carries the response, once read.
 *
 * Only in memory: a body cannot outlive the process nor be requested again (download links are
 * often single-use), so a lost stream ends the download with [DownloadException.StreamLost].
 */
@Singleton
class BrowserStreams @Inject constructor() {

    class Handle(val body: InputStream, val release: () -> Unit)

    private val pending = ConcurrentHashMap<String, Handle>()

    fun put(gameId: String, handle: Handle) {
        pending.put(gameId, handle)?.let(::dispose)
    }

    /** The stream for [gameId], removed from the registry: the caller now owns it. */
    fun take(gameId: String): Handle? = pending.remove(gameId)

    fun discard(gameId: String) {
        pending.remove(gameId)?.let(::dispose)
    }

    private fun dispose(handle: Handle) {
        runCatching { handle.body.close() }
        handle.release()
    }

    companion object {
        /** Prefix of a download row's URL when its bytes come from the browser, not from HTTP. */
        const val SCHEME = "browser-stream:"
    }
}
