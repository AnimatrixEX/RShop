package com.rshop.domain.catalog

/**
 * What a ROM title says about itself in its parentheses: "Sonic (USA, Europe)", "Game (Demo)",
 * "Game (Beta 2)". Read once when a game is stored, so catalogue filters stay plain SQL.
 */
object TitleTags {
    const val USA = 1
    const val EUROPE = 2
    const val JAPAN = 4
    const val WORLD = 8
    /** Any other region (Korea, Brazil, Asia…): known, but not one the region filter offers. */
    const val OTHER = 16

    private val GROUP = Regex("[(\\[]([^)\\]]*)[)\\]]")
    private val LETTER_CODES = Regex("[UEJ]{1,3}")

    private val usa = setOf("usa", "us", "america", "north america")
    private val europe = setOf(
        "europe", "eur", "uk", "united kingdom", "germany", "france", "spain", "italy", "netherlands",
        "sweden", "scandinavia", "denmark", "norway", "finland", "poland", "portugal", "austria", "switzerland",
    )
    private val japan = setOf("japan", "jp", "jpn")
    private val world = setOf("world")
    private val other = setOf(
        "korea", "asia", "brazil", "china", "taiwan", "hong kong", "russia", "latin america", "canada",
        "australia", "new zealand", "mexico", "argentina", "india", "israel",
    )

    private val extra = Regex(
        "(?i)[(\\[]\\s*(demo|beta|proto|prototype|sample|kiosk|preview|trial|debug|alpha|promo)\\b",
    )

    /** Bit set of the regions named in [title]; 0 when it names none. */
    fun regionFlags(title: String): Int {
        var flags = 0
        for (group in GROUP.findAll(title)) {
            val content = group.groupValues[1].trim()
            if (LETTER_CODES.matches(content)) {
                // No-Intro short codes: "(U)", "(E)", "(J)", "(UE)".
                if ('U' in content) flags = flags or USA
                if ('E' in content) flags = flags or EUROPE
                if ('J' in content) flags = flags or JAPAN
                continue
            }
            for (token in content.split(',', '/').map { it.trim().lowercase() }) {
                flags = flags or when (token) {
                    in usa -> USA
                    in europe -> EUROPE
                    in japan -> JAPAN
                    in world -> WORLD
                    in other -> OTHER
                    else -> 0
                }
            }
        }
        return flags
    }

    /** Demos, betas, prototypes and the like: not the finished game. */
    fun isExtra(title: String): Boolean = extra.containsMatchIn(title)
}

/** The region the catalogue can be narrowed to. */
enum class CatalogRegion(val mask: Int) {
    All(0),
    Usa(TitleTags.USA or TitleTags.WORLD),
    Europe(TitleTags.EUROPE or TitleTags.WORLD),
    Japan(TitleTags.JAPAN or TitleTags.WORLD);

    /** The next region when the user cycles through them. */
    fun next(): CatalogRegion = entries[(ordinal + 1) % entries.size]
}
