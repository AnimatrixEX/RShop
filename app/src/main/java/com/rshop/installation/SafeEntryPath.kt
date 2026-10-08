package com.rshop.installation

/**
 * Turns an archive entry name into safe path segments, or refuses it. Archives are untrusted:
 * an entry like `../../Android/data/x` or `/etc/x` must never escape the destination folder.
 */
object SafeEntryPath {

    private val forbiddenChars = Regex("[\\u0000-\\u001f<>:\"|?*]")
    private val reservedNames = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$")

    /** Returns path segments, or null for entries that are only "." or empty (to be skipped). */
    fun normalize(name: String): List<String>? {
        val unified = name.replace('\\', '/')
        if (unified.startsWith("/")) throw InstallException.UnsafeEntry(name)
        if (Regex("^[A-Za-z]:").containsMatchIn(unified)) throw InstallException.UnsafeEntry(name)

        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        segments.forEach { segment ->
            if (segment == ".." || forbiddenChars.containsMatchIn(segment)) throw InstallException.UnsafeEntry(name)
            if (segment.trimEnd('.', ' ').isEmpty()) throw InstallException.UnsafeEntry(name)
        }
        return segments
    }

    /** A name safe for a single file or folder created by the app (platform, game title). */
    fun sanitizeName(name: String, fallback: String = "Game"): String {
        val cleaned = name
            .replace(forbiddenChars, " ")
            .replace('/', ' ')
            .replace('\\', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(120)
            .trim()
        return when {
            cleaned.isEmpty() -> fallback
            reservedNames.matches(cleaned) -> "_$cleaned"
            else -> cleaned
        }
    }
}
