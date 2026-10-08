package com.rshop.scraper.website

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Keeps generic download rules (`a[href*=download]`, `[class*=download] a`…) from picking up
 * links that merely mention downloads: menus, breadcrumbs, FAQ, emulator or BIOS pages, help.
 */
internal object DownloadLinkFilter {
    private val CHROME_TAGS = setOf("nav", "header", "footer", "aside")
    private val CHROME_NAMES = Regex("(?i)breadcrumb|menu|navbar|sidebar|widget|footer|site-header|pagination|related|comment")
    private val NOT_A_FILE = Regex(
        "(?i)\\b(faq|emulators?|émulateurs?|bios|help|aide|guides?|how\\s+to|comment\\s+(installer|télécharger)|tutori(al|el)s?|tuto|" +
            "limits?|limites?|forums?|contact|log\\s*in|sign\\s*(in|up)|register|inscription|connexion|premium|vip|" +
            "polic(y|ies)|dmca|terms|request|report|signaler)\\b",
    )
    private val STRUCTURE_NAMES = Regex("(?i)breadcrumb|navbar|site-header|site-footer")
    private val DOWNLOAD_LABEL = Regex("(?i)^\\W*(download|télécharger)\\b")
    private val CATEGORY_ONLY = Regex("(?i)^\\W*(roms?|isos?|games?|jeux|home|accueil|downloads|téléchargements|all|tous)\\W*$")

    /** Links of [document] pointing to [url]. */
    fun anchors(document: Document, url: HttpUrl): List<Element> =
        document.select("a[href]").filter { it.absUrl("href").toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build() == url }

    /** True when at least one link to [url] looks like a download of this page's game. */
    fun accepts(document: Document, url: HttpUrl): Boolean {
        val anchors = anchors(document, url)
        // Found by a rule on something else than a link (meta, data attribute): nothing to judge.
        if (anchors.isEmpty()) return true
        return anchors.any(::isGameDownload)
    }

    private fun isGameDownload(anchor: Element): Boolean {
        // "Download ROM", "Download (ZIP)": a real button even inside a dropdown-menu of formats.
        // Only the site's own header, footer, navigation and breadcrumbs rule it out.
        val labelled = DOWNLOAD_LABEL.containsMatchIn(anchor.text())
        val names = if (labelled) STRUCTURE_NAMES else CHROME_NAMES
        if (anchor.parents().any { parent ->
                parent.tagName() in CHROME_TAGS ||
                    names.containsMatchIn(parent.className()) ||
                    names.containsMatchIn(parent.id())
            }
        ) {
            return false
        }
        val text = listOf(anchor.text(), anchor.attr("title"), anchor.attr("aria-label")).joinToString(" ").trim()
        if (text.isEmpty()) return true
        if (CATEGORY_ONLY.matches(anchor.text())) return false
        return !NOT_A_FILE.containsMatchIn(text)
    }
}
