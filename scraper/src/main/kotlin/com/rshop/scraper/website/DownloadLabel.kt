package com.rshop.scraper.website

/**
 * Short label telling a game's files apart, from the link text and the file name:
 * "Download (ZIP, 12 MB)" → "ZIP, 12 MB"; "Download Super Game" → null (says nothing new);
 * "Disc 2" + "game-d2.chd" → "Disc 2 · CHD".
 */
internal object DownloadLabel {
    private val LEADING_VERB = Regex("(?i)^[^\\p{L}\\p{N}]*(download|télécharger|get|dl)\\b[\\s:–—-]*")
    private val WRAPPING = Regex("^[(\\[](.*)[)\\]]$")
    private const val MAX_LENGTH = 48

    fun of(linkText: String?, fileName: String?, gameTitle: String): String? {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        var text = linkText?.replace(LEADING_VERB, "")?.trim().orEmpty()
        // The game title adds nothing on its own game page.
        if (text.contains(gameTitle, ignoreCase = true)) text = text.replace(gameTitle, "", ignoreCase = true)
        text = text.replace(Regex("\\s+"), " ").trim()
        // "(ZIP)" → "ZIP", but "ROM (ZIP)" keeps its parentheses.
        WRAPPING.matchEntire(text)?.let { text = it.groupValues[1].trim() }
        if (text.equals("now", ignoreCase = true) || text.equals("here", ignoreCase = true) || text.equals("ici", ignoreCase = true)) text = ""
        val format = extension?.uppercase()?.takeIf { !text.contains(it, ignoreCase = true) }
        return listOfNotNull(text.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH), format).joinToString(" · ").ifEmpty { null }
    }
}
