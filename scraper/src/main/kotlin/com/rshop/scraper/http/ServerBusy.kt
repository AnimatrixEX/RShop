package com.rshop.scraper.http

import org.jsoup.nodes.Document

/**
 * Recognises the "server is busy, try again later" notices that download pages show when the
 * site is overloaded or limits simultaneous downloads. Such a page is a wait, not a missing link.
 */
object ServerBusy {
    private val NOTICE = Regex(
        "(?i)(?:server|serveur|service|site)\\s+(?:is\\s+|est\\s+)?(?:currently\\s+|actuellement\\s+|too\\s+|très\\s+)?(?:busy|overloaded|unavailable|occupé|surchargé|indisponible)" +
            "|too many (?:users|requests|downloads|connections|visitors)" +
            "|trop de (?:requêtes|téléchargements|connexions|visiteurs)" +
            "|(?:try|please try) again (?:later|in a few)" +
            "|(?:réessayez|veuillez réessayer) (?:plus tard|dans quelques)" +
            "|high (?:traffic|load)",
    )

    /** Pages long enough to be a real article are not a busy notice that merely mentions the words. */
    private const val MAX_TEXT = 3_000

    fun isBusy(document: Document): Boolean {
        val text = document.body().text()
        return text.length <= MAX_TEXT && NOTICE.containsMatchIn(text)
    }
}
