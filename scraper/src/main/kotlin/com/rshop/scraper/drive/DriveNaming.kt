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
    // The patterns below are built from the word lists of NamingRules: conventions live there.
    private val UPDATE = NamingRules.any(NamingRules.UPDATE_WORDS)
    private val DLC = NamingRules.any(NamingRules.DLC_WORDS)
    private val UPDATE_TAG = Regex("(?i)\\b(${NamingRules.any(NamingRules.UPDATE_TAG_WORDS)})\\b")
    /** "DLC", "34DLC", "2DLCPack", "4 Updated DLCs", "add-on": the word anywhere in the tag. */
    private val DLC_TAG = Regex("(?i)$DLC")
    private val UPDATE_IN_NAME = Regex("(?i)[\\s+_.-]($UPDATE)(?:\\s*v?\\d|\\s*$)")
    private val DLC_IN_NAME = Regex("(?i)[\\s+_.-]\\d*(?:$DLC)\\b")
    /** From the add-on word to the end: "Game Update 1.2", "Game-Update150", "Game DLC Pack". */
    private val ADD_ON_CUT = Regex("(?i)[\\s+_.-]*(?:\\b|(?<=\\d))($UPDATE|$DLC)(?:\\b|(?=\\d)).*$")
    private val LOOSE_VERSION = Regex("(?i)(?<=^|\\s)v\\d+(?=\\s|$)")
    /** The count in "Grip 18 DLC". */
    private val DLC_COUNT = Regex("(?i)[\\s+_.-]+\\d{1,3}[\\s_.-]*(?=(?:$DLC)\\b)")
    private val TRAILING_VERSION = Regex("(?i)\\s+v\\d+(?:\\.\\d+)*$")
    /** Split archives: "Game.part1" (the extension is already gone). */
    private val PART = Regex("(?i)[.\\s_-]part\\s?\\d+$")
    /** An inner extension left by "Game.nsp.rar" or "Game.nsp.nsp". */
    private val INNER_EXTENSION = Regex("(?i)\\.(${NamingRules.any(NamingRules.INNER_EXTENSIONS)})$")
    /** Release-group words of scene names: "NAME-(USA)-NSwTcH-NSP-Ziperto". */
    private val SCENE = Regex("(?i)(?<=^|[-_\\s.])(?:${NamingRules.any(NamingRules.SCENE_WORDS)})(?=$|[-_\\s.])")
    private val DASH_RUNS = Regex("\\s*-(?:\\s*-)+\\s*")
    private val VERSION_TAG = Regex("(?i)^v\\s?(\\d+(?:\\.\\d+)*)$")
    private val VERSION_IN_NAME = Regex("(?i)\\bv(\\d+(?:\\.\\d+)+)\\b")
    private val VERSION_AFTER_WORD = Regex("(?i)\\b(?:$UPDATE)\\s*v?(\\d+(?:\\.\\d+)+)\\b")
    private val DEMO = Regex("(?i)\\b(${NamingRules.any(NamingRules.DEMO_WORDS)})\\b")
    private val SEPARATORS = Regex("[,+/&]")
    private val SPACES = Regex("\\s+")

    /** What a parenthesis may hold and still be a tag rather than part of the title. */
    private val TAG_TOKEN = Regex("(?i)" + NamingRules.any(NamingRules.REGIONS + NamingRules.TAG_PATTERNS))

    /** [isFile]: the name has an extension to drop. */
    fun parse(name: String, isFile: Boolean): Parsed {
        var stem = if (isFile) GameFiles.titleOf(name) else name.replace('_', ' ').trim()
        stem = PART.replace(stem, "")
        if (isFile) stem = INNER_EXTENSION.replace(stem, "")
        stem = SCENE.replace(stem, " ").trim().ifEmpty { stem }
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
            if (match.value.startsWith("(") && inner.isNotBlank() && !onlyTags(inner)) match.value else " "
        }
        // An id or a "v0" left outside brackets: "GRIP__0100459009A2A000__v0_NSP".
        title = LOOSE_VERSION.replace(SWITCH_ID.replace(title, " "), " ")
        if (role == Role.Dlc) title = DLC_COUNT.replace(title, " ")
        if (role != Role.Base) title = TRAILING_VERSION.replace(ADD_ON_CUT.replace(title, "").trimEnd(), "")
        title = DASH_RUNS.replace(title.replace(SPACES, " "), " - ").trim(' ', '-', '_', '.', '+', ',')
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
