package com.rshop.scraper.parse

/** Recognises console names, to tell console links apart from game links. */
object ConsoleNames {
    private const val CONSOLES =
        "nes|snes|super nintendo|famicom|nintendo|game ?boy|gba|gbc|nds|3ds|n64|gamecube|wii|switch|" +
            "playstation|psx|ps1|ps2|ps3|psp|vita|sega|mega ?drive|genesis|master system|game gear|saturn|dreamcast|" +
            "atari|neo ?geo|pc engine|turbografx|mame|arcade|amiga|commodore|c64|msx|spectrum|amstrad|" +
            "wonderswan|lynx|jaguar|3do|colecovision|intellivision|virtual boy|vectrex"

    /** Only meaningful as a whole label: "DS" or "Xbox" inside a game title says nothing. */
    private const val MORE_CONSOLES =
        "ds|dsi|ps4|ps vita|sega cd|mega cd|32x|zx spectrum|xbox|odyssey|pok[eé]mon mini|super famicom|$CONSOLES"

    /** Somewhere in a short label ("NES (120)", "Mega Drive ROMs"). */
    val CONTAINS = Regex("(?i)\\b($CONSOLES)\\b")

    private const val BRANDS = "nintendo|sega|sony|atari|snk|nec|microsoft|bandai|commodore|magnavox|philips|panasonic|sinclair"
    private const val SUFFIXES =
        "\\d+|[ivx]{1,3}|advance|colou?r|pocket|portable|classic|mini|xl|sp|micro|plus|go|cd|32x|one|series [xs]|u|" +
            "entertainment system|\\d+-?bit|handheld|console"

    /** The whole label is a console: "Nintendo GameCube", "Game Boy Advance ROMs", "PS2 (4 512)". */
    private val WHOLE = Regex(
        "(?i)^\\W*(?:($BRANDS)\\s+)?($MORE_CONSOLES)(?:\\s+($SUFFIXES))*" +
            "(?:\\s+(?:roms?|games?|jeux|isos?))?(?:\\s*[(\\[]\\s*[\\d\\s,.]+[)\\]])?\\W*$",
    )

    fun isConsoleName(text: String): Boolean = text.length <= 40 && WHOLE.matches(text.trim())
}
