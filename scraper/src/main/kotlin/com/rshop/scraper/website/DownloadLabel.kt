package com.rshop.scraper.website

/**
 * Short, readable label telling a game's files apart, from the link text and the file name:
 * "Download (ZIP, 12 MB)" → "ZIP, 12 MB"; "Download Super Game" → null (says nothing new);
 * "Disc 2" + "game-d2.chd" → "Disc 2 · CHD". When the link says nothing useful, the file name
 * is cleaned up instead ("Super_Mario_World_%28USA%29.zip" → "Super Mario World (USA) · ZIP").
 */
internal object DownloadLabel {
    private val LEADING_VERB = Regex("(?i)^[^\\p{L}\\p{N}]*(download|télécharger|telecharger|get|dl)\\b[\\s:–—-]*")
    private val WRAPPING = Regex("^[(\\[](.*)[)\\]]$")
    private val PERCENT = Regex("%[0-9a-fA-F]{2}")
    private val COMPOUND = listOf("tar.gz", "tar.xz", "tar.bz2", "tar.zst")
    private const val MAX_LENGTH = 90

    /** Upper-case format of a file name ("ZIP", "TAR.GZ", "CHD"), null when it has no real extension. */
    fun formatOf(fileName: String?): String? {
        val name = fileName?.lowercase()?.trim() ?: return null
        COMPOUND.firstOrNull { name.endsWith(".$it") }?.let { return it.uppercase() }
        val extension = name.substringAfterLast('.', "")
        return extension.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) && !it.all(Char::isDigit) }?.uppercase()
    }

    fun of(linkText: String?, fileName: String?, gameTitle: String): String? {
        val format = formatOf(fileName)
        var text = linkText?.replace(LEADING_VERB, "")?.trim().orEmpty()
        // The game title adds nothing on its own game page.
        text = removeTitle(text, gameTitle)
        text = text.replace(Regex("\\s+"), " ").trim()
        // "(ZIP)" → "ZIP", but "ROM (ZIP)" keeps its parentheses.
        WRAPPING.matchEntire(text)?.let { text = it.groupValues[1].trim() }
        if (text.lowercase() in NOISE_WORDS) text = ""
        // The link says nothing: what is left of the file name once the game title is removed
        // ("(USA) (Rev 1)", "Disc 2") tells files apart better than nothing.
        if (text.isEmpty() && fileName != null) text = removeTitle(readable(fileName), gameTitle).replace(Regex("\\s+"), " ").trim()
        if (text.length < 2 || text.all { !it.isLetterOrDigit() }) text = ""
        val shownFormat = format?.takeIf { !text.contains(it, ignoreCase = true) }
        return listOfNotNull(text.takeIf { it.isNotEmpty() }?.let(::shorten), shownFormat).joinToString(" · ").ifEmpty { null }
    }

    /** "Super_Mario_World_%28USA%29.v1.1.zip" → "Super Mario World (USA) v1.1". */
    fun readable(fileName: String): String {
        var name = fileName
        if (PERCENT.containsMatchIn(name)) name = runCatching { java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name)
        formatOf(name)?.let { format -> name = name.dropLast(format.length + 1) }
        name = name.replace('_', ' ')
        // A slug ("super-game-usa") is words joined by dashes.
        if (' ' !in name) name = name.replace('-', ' ')
        // "Super.Mario.World" → spaces, but keep dots inside versions ("v1.1").
        if (' ' !in name && name.count { it == '.' } >= 2) name = name.replace(Regex("(?<=\\p{L})\\.(?=\\p{L})"), " ")
        return name.replace(Regex("\\s+"), " ").trim(' ', '-', '.')
    }

    private fun removeTitle(text: String, title: String): String {
        if (title.isBlank() || !text.contains(title, ignoreCase = true)) return text
        return text.replace(title, "", ignoreCase = true).trim(' ', '-', '–', '—', ':', '_', '.')
    }

    private fun shorten(text: String): String =
        if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH - 1).trimEnd() + "…"

    private val NOISE_WORDS = setOf("now", "here", "ici", "link", "lien", "file", "fichier", "rom", "game", "jeu")
}
