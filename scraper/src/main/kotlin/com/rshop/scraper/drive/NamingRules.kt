package com.rshop.scraper.drive

/**
 * Every word list the Drive name reader works from, in one place. A new naming convention is a new
 * word or pattern here, never a game name: [DriveNaming] and [GameFiles] build their regexes from
 * these lists. Entries are regex fragments, matched without case.
 */
internal object NamingRules {

    /** Words that make a file or folder name an update when they stand in it: "Game Update 1.2", "Game-Update150". */
    val UPDATE_WORDS = listOf("update", "patch")

    /** Words that make a bracket or parenthesis an update tag: "[UPD]", "[60FPS MOD]". */
    val UPDATE_TAG_WORDS = UPDATE_WORDS + listOf("upd", "mod")

    /** Words of downloadable content, in a name or a tag: "DLC", "34DLC", "2DLCPack", "Add-ons". */
    val DLC_WORDS = listOf("dlcs?", "add-?ons?")

    /** Names of a game's sub-folder that holds its updates: "Update", "Patches", "Mise à jour". */
    val UPDATE_FOLDER_WORDS = listOf("updates?", "patch(?:e?s)?", "maj", "mises?\\s+[àa]\\s+jour")

    /** Release-group and format words dropped from titles: "NAME-(USA)-NSwTcH-NSP-Ziperto". */
    val SCENE_WORDS = listOf("nswtch", "nsw", "nsp", "nsz", "xci", "xcz", "ziperto")

    /** Extensions left inside a name by "Game.nsp.rar" or "Game.nsp.nsp". */
    val INNER_EXTENSIONS = listOf("nsp", "nsz", "xci", "xcz")

    /** Words that keep a parenthesis in the title: "(Demo)" is part of what the game is. */
    val DEMO_WORDS = listOf("demo", "beta", "proto", "prototype", "sample", "kiosk", "preview", "trial", "alpha", "promo")

    /** Regions and languages: a parenthesis of these only is a tag, not part of the title. */
    val REGIONS = listOf(
        "usa", "us", "europe", "eur", "eu", "japan", "jpn", "jp", "world", "asia", "korea", "china", "taiwan", "hong kong",
        "australia", "brazil", "canada", "france", "germany", "italy", "spain", "netherlands", "sweden", "norway", "denmark",
        "finland", "russia", "uk", "ntsc(?:-[uj])?", "pal", "multi ?\\d*", "[a-z]{2}(?:-[a-z]{2})?",
    )

    /** Other tag contents: revisions, versions, discs, counts of add-ons, dump marks, ids. */
    val TAG_PATTERNS = listOf(
        "rev ?[\\w.]+", "v ?\\d[\\w.]*", "\\d+(?:\\.\\d+)+", "update.*", "patch.*", "\\d*\\s*(?:updated\\s+)?dlc.*",
        "disc ?\\d+", "disk ?\\d+", "cd ?\\d+", "track ?\\d+", "part ?\\d+", "unl", "hack", "translated.*", "!",
        "[0-9a-f]{8,}", "\\d+", "nsp", "xci", "nsz", "digital", "eshop", "retail", "scene.*", "decrypted", "cia", "3ds",
    )

    /** Folders that only sort games: a game folder never has these names. */
    val GROUPING_NAMES = setOf(
        "roms", "rom", "games", "game", "jeux", "isos", "iso", "europe", "eur", "usa", "us", "japan", "jpn", "jp", "world", "pal",
        "ntsc", "translations", "traductions", "hacks", "homebrew", "collection", "all", "misc", "other", "autres", "numbers",
        "symbols",
    )

    /** Joins fragments into one alternation. */
    fun any(words: Collection<String>): String = words.joinToString("|")
}
