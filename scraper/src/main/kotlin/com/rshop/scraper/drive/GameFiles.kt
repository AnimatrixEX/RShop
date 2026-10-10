package com.rshop.scraper.drive

/** What counts as a game file in a folder, and how a title is read from a file or folder name. */
internal object GameFiles {
    private val ARCHIVES = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "xz", "txz", "bz2", "zst")
    private val DISCS = setOf(
        "iso", "bin", "cue", "chd", "cso", "pbp", "img", "mdf", "mds", "nrg", "gdi", "cdi", "ccd", "m3u", "rvz", "gcz", "gcm", "wbfs", "wia",
    )
    private val CARTRIDGES = setOf(
        "nes", "unf", "fds", "sfc", "smc", "fig", "swc", "gb", "gbc", "gba", "nds", "dsi", "3ds", "cia", "cci", "n64", "z64", "v64", "ndd",
        "md", "gen", "smd", "sms", "gg", "sg", "32x", "pce", "sgx", "ngp", "ngc", "ws", "wsc", "a26", "a52", "a78", "lnx", "j64", "jag",
        "vb", "vboy", "col", "int", "vec", "nsp", "xci", "nsz", "xcz", "wad", "prg", "d64", "t64", "tap", "adf", "dsk", "st", "tzx", "mx1", "mx2",
    )
    private val EXTENSIONS = ARCHIVES + DISCS + CARTRIDGES

    /** Multi-part extensions that end in an archive extension ("game.tar.gz"). */
    private val DOUBLE = Regex("(?i)\\.tar\\.(gz|xz|bz2|zst)$")

    /** Never offered: programs and scripts (the downloader refuses them anyway), and folder litter. */
    private val EXCLUDED = setOf(
        "apk", "apks", "xapk", "aab", "exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js",
        "jar", "dex", "dll", "so", "sh", "app", "dmg", "deb", "rpm", "html", "htm",
    )
    private val LITTER = setOf("desktop.ini", "thumbs.db", ".ds_store")

    /** Offered next to the game but not what tells a folder holds one: pictures, notes, checksums. */
    private val SIDE = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "txt", "nfo", "md", "pdf", "url", "xml", "sfv", "md5", "sha1",
        "sha256", "json", "ini", "db", "lnk", "log", "diz",
    )

    /** Every file a game folder shows, whatever its type, except programs and litter. */
    fun isOffered(name: String): Boolean {
        val lower = name.lowercase()
        return lower !in LITTER && !lower.startsWith(".") && extensionOf(name) !in EXCLUDED
    }

    /** A file that is the game itself (archive, disc image, ROM, any format not known as a side file). */
    fun isGameFile(name: String): Boolean {
        val extension = extensionOf(name)
        return isOffered(name) && extension.isNotEmpty() && extension !in SIDE
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** The name without its extension: "Advance Wars.zip" → "Advance Wars". */
    fun titleOf(fileName: String): String {
        val withoutDouble = DOUBLE.replace(fileName, "")
        val base = if (withoutDouble != fileName || extensionOf(fileName) !in EXTENSIONS) withoutDouble else fileName.substringBeforeLast('.')
        return base.replace('_', ' ').trim().ifEmpty { fileName }
    }

    /**
     * Files that belong to the same game share this key: "Game (Disc 1).cue" and "Game (Disc 2).cue",
     * "Game.cue" and "Game.bin", "Game (Track 01).bin".
     */
    fun groupKey(fileName: String): String = baseTitle(fileName).lowercase()

    /** The title without disc or track tags: "Game (Disc 2).chd" → "Game". */
    fun baseTitle(fileName: String): String {
        val title = titleOf(fileName)
        return PART_TAGS.replace(title, " ").replace(SPACES, " ").trim().ifEmpty { title }
    }

    private val SPACES = Regex("\\s+")

    private val PART_TAGS = Regex(
        "(?i)[(\\[]\\s*(?:disc|disk|cd|dvd|track|part|side)\\s*[0-9a-z]+(?:\\s*(?:of|/)\\s*\\d+)?\\s*[)\\]]" +
            "|\\b(?:disc|disk|cd|dvd|track)\\s*\\d+\\b|\\.part\\d+$",
    )

    private val CONTAINER_NAMES = setOf(
        "roms", "rom", "games", "game", "jeux", "isos", "iso", "europe", "eur", "usa", "us", "japan", "jpn", "jp", "world", "pal", "ntsc",
        "translations", "traductions", "hacks", "homebrew", "collection", "all", "misc", "other", "autres", "numbers", "symbols",
    )

    /**
     * A folder that only sorts games ("A", "A-C", "0-9", "Europe", "Roms") instead of being one: its
     * files are games of their own even when there is a single file.
     */
    fun isGroupingName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n in CONTAINER_NAMES || LETTERS.matches(n)
    }

    /**
     * A sub-folder of a game folder holding the game's add-ons: "Update", "Updates v1.2", "Patch",
     * "Mise à jour", "DLC", "DLC (3)", "Add-ons" ("DLC Quest" is a game, not a DLC folder).
     */
    fun isUpdateFolderName(name: String): Boolean = UPDATE_FOLDER.matches(name.trim()) || DLC_FOLDER.matches(name.trim())

    private val UPDATE_FOLDER = Regex("(?i)(?:updates?|patch(?:e?s)?|maj|mises?\\s+[àa]\\s+jour)(?:[\\s_.-].*)?")
    private val DLC_FOLDER = Regex("(?i)(?:dlcs?|add-?ons?)(?:\\s*[\\d(\\[].*)?")

    /** A name that holds no title: a Switch id, a version, a long number ("1942" is a game). */
    fun isTitleless(title: String): Boolean = TITLE_ID.matches(title.replace(" ", ""))

    private val TITLE_ID = Regex("(?i)[0-9a-f]{16}|v\\d+(?:\\.\\d+)*|\\d+(?:\\.\\d+)+|\\d{5,}")

    private val LETTERS = Regex("[a-z0-9#]|[a-z0-9#]\\s*[-–]\\s*[a-z0-9#]|0\\s*[-–]\\s*9")
}
