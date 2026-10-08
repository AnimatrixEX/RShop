package com.rshop.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import com.rshop.ui.browser.NavigationPolicy.Decision
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import java.io.InputStream

/** What the screen shows about the browser (title, address, progress, tabs, a held-back pop-up). */
data class BrowserState(
    val title: String = "",
    val host: String = "",
    /** Full URL of the visible tab, to hand to the device browser. */
    val url: String = "",
    val progress: Int = 0,
    val tabCount: Int = 1,
    val tabIndex: Int = 0,
    /** A page the site tried to open on another site, waiting for the user's choice. */
    val blocked: String? = null,
)

/** A file the page handed over for download: the real HTTP response, read straight into the app. */
class GeckoDownload(
    val uri: String,
    val fileName: String?,
    val contentType: String?,
    val contentLength: Long?,
    val body: InputStream?,
    /** Closes the page session carrying [body]; call once the body is read or dropped. Any thread. */
    val release: () -> Unit,
)

/**
 * The in-app browser, running Gecko (Firefox's engine) — a real browser, not an Android WebView,
 * so sites do not flag it as an in-app view. Downloads are read directly into the app (no trip
 * through the system browser), ads and trackers are blocked by the runtime, and pop-ups towards
 * other sites than the clicked link are held back by [NavigationPolicy].
 */
@SuppressLint("ViewConstructor")
class GeckoBrowser(
    context: Context,
    private val runtime: GeckoRuntime,
    private val onState: (BrowserState) -> Unit,
    private val onDownload: (GeckoDownload) -> Unit,
) : FrameLayout(context) {

    private val view = GeckoView(context)
    private val tabs = mutableListOf<GeckoSession>()
    private val urls = HashMap<GeckoSession, String>()
    private val canGoBack = HashMap<GeckoSession, Boolean>()
    // New windows awaiting their first response: a file is captured, a page is shown or held back.
    private val probes = HashSet<GeckoSession>()
    private val probeOpener = HashMap<GeckoSession, String?>()
    // Sessions whose download body is still being read: kept open even after the screen closes.
    private val transferring = HashSet<GeckoSession>()
    private var destroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = BrowserState()
    private var pendingOpen: (() -> Unit)? = null

    private val current: GeckoSession? get() = tabs.lastOrNull()

    init {
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun open(url: String) {
        val session = newSession()
        session.loadUri(url)
    }

    /** Back inside the tab, else close the tab. False when there is nothing left to go back to. */
    fun goBack(): Boolean {
        val tab = current ?: return false
        if (canGoBack[tab] == true) {
            tab.goBack()
            return true
        }
        if (tabs.size > 1) {
            closeTab(tab)
            return true
        }
        return false
    }

    fun closeCurrentTab() = current?.takeIf { tabs.size > 1 }?.let(::closeTab)

    fun acceptBlocked() {
        val action = pendingOpen
        dismissBlocked()
        action?.invoke()
    }

    fun dismissBlocked() {
        pendingOpen = null
        publish(state.copy(blocked = null))
    }

    fun destroy() {
        destroyed = true
        (tabs + probes).filterNot { it in transferring }.forEach { it.close() }
        tabs.clear()
        probes.clear()
        probeOpener.clear()
        urls.clear()
        canGoBack.clear()
        removeAllViews()
    }

    private fun newSession(): GeckoSession {
        val session = GeckoSession()
        configure(session)
        session.open(runtime)
        tabs += session
        showTop()
        return session
    }

    /** A hidden, unopened session whose first response decides: file captured, page shown or held back. */
    private fun probe(openerUrl: String?): GeckoSession {
        val probe = GeckoSession()
        configure(probe)
        probes += probe
        probeOpener[probe] = openerUrl
        return probe
    }

    /** The download body of [session] was read (or dropped): close it unless it is a visible tab. */
    private fun releaseTransfer(session: GeckoSession) {
        if (!transferring.remove(session)) return
        if (destroyed || session !in tabs) session.close()
    }

    private fun closeTab(tab: GeckoSession) {
        tabs.remove(tab)
        urls.remove(tab)
        canGoBack.remove(tab)
        if (tab !in transferring) tab.close()
        showTop()
    }

    private fun showTop() {
        val tab = current
        if (tab != null) view.setSession(tab)
        publish(
            state.copy(
                title = state.title.takeIf { tab != null && urls[tab] == state.url } ?: "",
                host = NavigationPolicy.hostOf(tab?.let { urls[it] }).orEmpty(),
                url = tab?.let { urls[it] }.orEmpty(),
                tabCount = tabs.size,
                tabIndex = tabs.lastIndex.coerceAtLeast(0),
            ),
        )
    }

    private fun publish(newState: BrowserState) {
        state = newState
        onState(newState)
    }

    private fun ask(uri: String, open: () -> Unit) {
        pendingOpen = open
        publish(state.copy(blocked = NavigationPolicy.hostOf(uri) ?: uri))
    }

    private fun configure(session: GeckoSession) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val target = request.uri
                // Gecko keeps a click's user activation for seconds, so a script hijacking the tab
                // right after a click also "has a gesture": it cannot tell the clicked link apart.
                return when (NavigationPolicy.navigation(target, urls[session], null, request.isRedirect)) {
                    Decision.Allow -> GeckoResult.allow()
                    Decision.Block -> GeckoResult.deny()
                    Decision.Ask -> {
                        // Another site after a click: load it hidden first. A file (mirror host) is
                        // captured; a page waits behind the banner, so the tab is never hijacked.
                        if (request.hasUserGesture) {
                            probe(urls[session]).apply { open(runtime) }.loadUri(target)
                        } else {
                            ask(target) { session.loadUri(target) }
                        }
                        GeckoResult.deny()
                    }
                }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Open the new window hidden and watch what it does: a download link yields a file
                // (captured, no tab shown); a real page is shown or held back as a pop-up. This is
                // what makes "download opens in a new tab" (often on another host) work.
                // Gecko opens the returned session itself: it must not be opened here.
                return GeckoResult.fromValue(probe(urls[session]))
            }

            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                canGoBack[session] = value
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                // A watched new window turned out to be a real page, not a download.
                if (session in probes) {
                    // New windows start on about:blank before their real address.
                    if (url.startsWith("about:", ignoreCase = true)) return
                    probes.remove(session)
                    when (NavigationPolicy.popup(url, probeOpener.remove(session), null)) {
                        // The clicked link opens in a new tab: show it.
                        Decision.Allow -> {
                            tabs += session
                            urls[session] = url
                            showTop()
                        }
                        // A pop-up/pop-under towards another site: refused, offered behind a banner.
                        Decision.Ask -> { ask(url) { open(url) }; session.close() }
                        Decision.Block -> session.close()
                    }
                    return
                }
                urls[session] = url
                if (session == current) publish(state.copy(url = url, host = NavigationPolicy.hostOf(url).orEmpty(), title = ""))
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (session == current) publish(state.copy(progress = progress))
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (session == current) publish(state.copy(title = title.orEmpty()))
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val headers = CaseInsensitiveHeaders(response.headers)
                // Closing the session would cut the body: it stays open until the file is read.
                transferring += session
                val release = { mainHandler.post { releaseTransfer(session) }; Unit }
                onDownload(
                    GeckoDownload(
                        uri = response.uri,
                        fileName = fileNameFromDisposition(headers["Content-Disposition"]),
                        contentType = headers["Content-Type"]?.substringBefore(';')?.trim(),
                        contentLength = headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0 },
                        body = response.body,
                        release = release,
                    ),
                )
                // A window opened only to hand over the file has nothing to show (closed on release).
                if (session in probes) {
                    probes.remove(session)
                    probeOpener.remove(session)
                } else if (tabs.size > 1 && session == current && urls[session] == null) {
                    closeTab(session)
                }
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            // Script pop-ups blocked by Gecko's pop-up blocker: keep them blocked.
            override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // One pop-up per click: when an ad script used it first, the real target=_blank
                // download link gets blocked. A blocked window on the same site is still probed.
                val target = prompt.targetUri
                val opener = urls[session]
                if (target != null && opener != null && NavigationPolicy.popup(target, opener, null) == Decision.Allow) {
                    probe(opener).apply { open(runtime) }.loadUri(target)
                }
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
            }
        }
    }
}

/** Reads headers without caring about letter case ("Content-Type" vs "content-type"). */
private class CaseInsensitiveHeaders(source: Map<String, String>) {
    private val map = source.entries.associate { it.key.lowercase() to it.value }
    operator fun get(name: String): String? = map[name.lowercase()]
}

/** `attachment; filename="game.zip"` or `filename*=UTF-8''game.zip` → `game.zip`. */
private val DISPOSITION_NAME = Regex("(?i)filename\\*?=(?:UTF-8'')?\"?([^\";]+)")

private fun fileNameFromDisposition(disposition: String?): String? =
    disposition?.let { DISPOSITION_NAME.find(it)?.groupValues?.get(1) }
        ?.let { runCatching { java.net.URLDecoder.decode(it.trim('"', ' '), "UTF-8") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
