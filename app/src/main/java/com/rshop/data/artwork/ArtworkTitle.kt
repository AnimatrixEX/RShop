package com.rshop.data.artwork

/** Turns catalogue titles into SteamGridDB search terms and picks the matching result. */
object ArtworkTitle {
    private val EXTENSION = Regex("(?i)\\.(zip|7z|rar|tar|gz|iso|chd|cue|bin|rvz|nsp|xci|cia|3ds|nes|sfc|smc|gba|gbc|gb|nds|n64|z64|md|gen|sms|gg|pce|wbfs|pbp|cso)$")
    private val TAGS = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val DISC = Regex("(?i)\\s*[-–,]?\\s*(disc|disk|cd)\\s*\\d+\\b.*$")
    private val TRAILING_ARTICLE = Regex("(?i)^(.+?),\\s*(the|a|an)(\\s*[-:].*)?$")
    private val SPACES = Regex("\\s+")

    /** "Legend of Zelda, The - A Link to the Past (USA) [!].zip" → "The Legend of Zelda - A Link to the Past". */
    fun searchTerm(title: String): String {
        var text = title.replace('_', ' ').trim()
        text = text.replace(EXTENSION, "").replace(TAGS, "").replace(DISC, "")
        TRAILING_ARTICLE.matchEntire(text)?.let { match ->
            text = "${match.groupValues[2]} ${match.groupValues[1]}${match.groupValues[3]}"
        }
        return text.replace(SPACES, " ").trim().ifEmpty { title.trim() }
    }

    /**
     * Same name (ignoring case and punctuation) first, then a near-identical one ("Pokemon Red"
     * vs "Pokemon Red Version"). Anything looser is refused: no cover beats another game's cover.
     */
    fun pick(results: List<SgdbGame>, term: String): SgdbGame? {
        val wanted = normalize(term)
        if (wanted.isEmpty()) return null
        results.firstOrNull { normalize(it.name) == wanted }?.let { return it }
        return results.filter { close(normalize(it.name), wanted) }.sortedByDescending { it.verified }.firstOrNull()
    }

    /** One name contains the other and they differ by little ("Mirror" is not "Mirror Maze"). */
    private fun close(a: String, b: String): Boolean {
        if (a.isEmpty()) return false
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return long.contains(short) && short.length >= long.length * MIN_OVERLAP
    }

    /** Raised when [pick] changes: every cover is looked up again once. */
    const val MATCHER_VERSION = 2
    private const val MIN_OVERLAP = 0.8

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
