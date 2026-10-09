package com.rshop.scraper.drive

/**
 * Reads what a dump's file or folder name says: the game's real title, whether the file is the game
 * itself, an update or a DLC, and which files belong together. Dumps are named by conventions, not
 * by a standard, so this is a best effort on the common ones:
 *
 * - Switch: `Title [0100ABCDEF012000][v0].nsp` (base game), `...800][v65536]` (update), any other
 *   id ending (DLC); the first 13 digits of the id are shared by the three;
 * - tags: `[UPDATE]`, `[DLC]`, `(Update 1.2)`, `Title + Update v1.0.2`;
 * - region, language and revision tags (`(USA) (En,Fr) (Rev 1)`) are not part of the title.
 */
internal object DriveNaming {

    enum class Role { Base, Update, Dlc }

    data class Parsed(
        /** The game's name without the tags dumps add to it. */
        val title: String,
        val role: Role,
        /** Files with the same key are one game and its add-ons. */
        val groupKey: String,
        /** "1.0.2" or "65536" when the name says. */
        val version: String?,
    )

    private val SWITCH_ID = Regex("(?i)(?<![0-9a-f])(0100[0-9a-f]{12})(?![0-9a-f])")
    private val GROUPS = Regex("\\[[^\\]]*]|\\([^)]*\\)")
    private val UPDATE_TAG = Regex("(?i)\\b(update|patch|upd)\\b")
    private val DLC_TAG = Regex("(?i)\\b(dlc|add-?ons?)\\b")
    private val UPDATE_IN_NAME = Regex("(?i)[\\s+_.-](update|patch)(?:\\s*v?\\d|\\s*$)")
    private val DLC_IN_NAME = Regex("(?i)[\\s+_.-]dlcs?\\b")
    private val ADD_ON_CUT = Regex("(?i)[\\s+_.-]*\\b(update|patch|dlcs?|add-?ons?)\\b.*$")
    private val VERSION_TAG = Regex("(?i)^v\\s?(\\d+(?:\\.\\d+)*)$")
    private val VERSION_IN_NAME = Regex("(?i)\\bv(\\d+(?:\\.\\d+)+)\\b")
    private val VERSION_AFTER_WORD = Regex("(?i)\\b(?:update|patch)\\s*v?(\\d+(?:\\.\\d+)+)\\b")
    private val DEMO = Regex("(?i)\\b(demo|beta|proto|prototype|sample|kiosk|preview|trial|alpha|promo)\\b")
    private val SEPARATORS = Regex("[,+/&]")
    private val SPACES = Regex("\\s+")

    /** What a parenthesis may hold and still be a tag rather than part of the title. */
    private val TAG_TOKEN = Regex(
        "(?i)usa|us|europe|eur|eu|japan|jpn|jp|world|asia|korea|china|taiwan|hong kong|australia|brazil|canada|france|germany|" +
            "italy|spain|netherlands|sweden|norway|denmark|finland|russia|uk|ntsc(?:-[uj])?|pal|multi ?\\d*|[a-z]{2}(?:-[a-z]{2})?|" +
            "rev ?[\\w.]+|v ?\\d[\\w.]*|\\d+(?:\\.\\d+)+|update.*|dlc.*|patch.*|disc ?\\d+|disk ?\\d+|cd ?\\d+|track ?\\d+|part ?\\d+|" +
            "unl|hack|translated.*|!|[0-9a-f]{8,}|\\d+|nsp|xci|nsz|digital|eshop|retail|scene.*|decrypted|cia|3ds",
    )

    /** [isFile]: the name has an extension to drop. */
    fun parse(name: String, isFile: Boolean): Parsed {
        var stem = if (isFile) GameFiles.titleOf(name) else name.replace('_', ' ').trim()
        // Scene style: Super.Mario.Odyssey.NSW
        if (!stem.contains(' ') && stem.count { it == '.' } >= 2) stem = stem.replace('.', ' ')

        val tags = GROUPS.findAll(stem).map { it.value.trim('[', ']', '(', ')').trim() }.toList()
        val outside = GROUPS.replace(stem, " ")
        val id = SWITCH_ID.find(stem)?.groupValues?.get(1)
        val role = when {
            id != null -> when (id.takeLast(3)) {
                "000" -> Role.Base
                "800" -> Role.Update
                else -> Role.Dlc
            }
            tags.any { UPDATE_TAG.containsMatchIn(it) } || UPDATE_IN_NAME.containsMatchIn(outside) -> Role.Update
            tags.any { DLC_TAG.containsMatchIn(it) } || DLC_IN_NAME.containsMatchIn(outside) -> Role.Dlc
            else -> Role.Base
        }
        val version = tags.firstNotNullOfOrNull { VERSION_TAG.find(it)?.groupValues?.get(1) }
            ?: VERSION_IN_NAME.find(stem)?.groupValues?.get(1)
            ?: VERSION_AFTER_WORD.find(stem)?.groupValues?.get(1)

        var title = GROUPS.replace(stem) { match ->
            val inner = match.value.trim('[', ']', '(', ')').trim()
            // Brackets are always noise; a parenthesis stays unless it only holds tags (a demo mark stays too).
            if (match.value.startsWith("(") && !onlyTags(inner)) match.value else " "
        }
        if (role != Role.Base) title = ADD_ON_CUT.replace(title, "")
        title = title.replace(SPACES, " ").trim(' ', '-', '_', '.', '+', ',')
        if (title.isEmpty()) title = stem.trim()

        val key = if (id != null) ID_PREFIX + baseId(id, role) else titleKey(title)
        return Parsed(title, role, key, version)
    }

    /**
     * The id of the game an id belongs to: an update is the game's id plus 0x800, a DLC's id is the game's
     * plus 0x1000 and its own number (so the three files of one game share this id).
     */
    private fun baseId(id: String, role: Role): String {
        val n = java.lang.Long.parseUnsignedLong(id, 16)
        val base = when (role) {
            Role.Base -> n
            Role.Update -> n - 0x800
            Role.Dlc -> (n - 0x1000) and 0xFFFL.inv()
        }
        return "%016x".format(base)
    }

    private fun onlyTags(content: String): Boolean {
        if (DEMO.containsMatchIn(content)) return false
        val tokens = content.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { TAG_TOKEN.matches(it) }
    }

    /** Titles that differ only by case and punctuation are one title. */
    fun titleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    /** [Parsed.groupKey] of a game known by its Switch id (as opposed to its title). */
    const val ID_PREFIX = "id:"

    /** The mark shown next to an add-on in the file list. */
    fun addOnLabel(name: String): String {
        val parsed = parse(name, isFile = true)
        return when {
            parsed.role == Role.Dlc -> "DLC"
            parsed.version != null && parsed.role == Role.Update -> "UPDATE v${parsed.version}"
            else -> "UPDATE"
        }
    }
}
