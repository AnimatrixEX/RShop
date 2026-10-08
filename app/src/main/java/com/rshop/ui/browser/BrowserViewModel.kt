package com.rshop.ui.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rshop.di.ApplicationScope
import com.rshop.domain.model.DownloadError
import com.rshop.domain.model.DownloadErrorKind
import com.rshop.download.DownloadException
import com.rshop.download.DownloadManager
import com.rshop.download.DownloadPolicy
import com.rshop.download.StartResult
import com.rshop.download.toDownloadError
import com.rshop.navigation.BrowserRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.mozilla.geckoview.GeckoRuntime
import timber.log.Timber
import javax.inject.Inject

sealed interface BrowserEvent {
    /** The file was handed to the download queue / installed: the browser closes. */
    data object Started : BrowserEvent
    data class Failed(val error: DownloadError) : BrowserEvent
    data object NoGamesDirectory : BrowserEvent
    data object Unsupported : BrowserEvent
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val runtime: GeckoRuntime,
    private val downloads: DownloadManager,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<BrowserRoute>()
    val startUrl: String = route.url

    private val _events = MutableStateFlow<BrowserEvent?>(null)
    val events: StateFlow<BrowserEvent?> = _events

    /** One download handled at a time, so an ad firing a second one cannot interrupt it. */
    @Volatile private var busy = false

    /**
     * A file the page handed over: its stream goes to the download queue, which reads it in the
     * background (the browser can be closed), then verifies and installs it. Executables and
     * scripts are refused before anything is written.
     */
    fun onDownload(download: GeckoDownload) {
        val refuse = { event: BrowserEvent? ->
            runCatching { download.body?.close() }
            download.release()
            if (event != null) _events.value = event
        }
        val url = download.uri.toHttpUrlOrNull() ?: return refuse(BrowserEvent.Unsupported)
        val name = DownloadPolicy.fileName(download.fileName, url, "download", download.contentType)
        try {
            DownloadPolicy.check(url, name) { true }
            DownloadPolicy.checkContentType(download.contentType)
        } catch (e: DownloadException) {
            return refuse(BrowserEvent.Failed(e.toDownloadError()))
        }
        if (busy) return refuse(null)
        busy = true
        // Not tied to the screen: closing the browser must not drop the hand-over.
        appScope.launch {
            try {
                // Some downloads arrive without a readable stream; fetch the URL through the same
                // engine (so its cookies and session still apply) to get the bytes.
                val body = download.body ?: refetch(download.uri)
                if (body == null) {
                    download.release()
                    _events.value = BrowserEvent.Failed(DownloadError(DownloadErrorKind.NoLink))
                    return@launch
                }
                _events.value = when (
                    val result = downloads.startBrowserStream(route.gameId, download.uri, name, download.contentLength, body, download.release)
                ) {
                    StartResult.Started, is StartResult.OpenInBrowser -> BrowserEvent.Started
                    StartResult.NoGamesDirectory -> BrowserEvent.NoGamesDirectory
                    is StartResult.Failed -> BrowserEvent.Failed(result.error)
                }
            } finally {
                busy = false
            }
        }
    }

    /** Fetches [uri] through the Gecko engine (keeping its cookies/session) when no stream was given. */
    private suspend fun refetch(uri: String): java.io.InputStream? = withContext(Dispatchers.IO) {
        try {
            val request = org.mozilla.geckoview.WebRequest.Builder(uri).build()
            org.mozilla.geckoview.GeckoWebExecutor(runtime).fetch(request).poll(REFETCH_TIMEOUT_MS)?.body
        } catch (e: Exception) {
            Timber.w(e, "Refetch of %s failed", uri)
            null
        }
    }

    fun onEventHandled() {
        _events.value = null
    }

    private companion object {
        const val REFETCH_TIMEOUT_MS = 60_000L
    }
}
