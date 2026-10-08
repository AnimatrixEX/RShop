package com.rshop.installation

/**
 * The format of an installed game, read from its file names: "GBA", "CHD", "BIN + CUE", "ZIP".
 * Files that are not the game itself (readmes, pictures, saves, playlists) are ignored.
 */
object FileFormats {

    private val ignored = setOf(
        "txt", "nfo", "md", "pdf", "diz", "url", "html", "htm", "xml", "json", "ini", "cfg", "log", "dat", "db",
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "mp4", "avi", "srm", "sav", "state", "m3u", "sfv", "md5", "sha1", "crc", "ds_store",
    )
    private const val MAX_FORMATS = 3

    /** Upper-case format of one file name, null when it has none (or is not a game file). */
    fun of(fileName: String): String? {
        val name = fileName.lowercase()
        listOf("tar.gz", "tar.xz", "tar.bz2").firstOrNull { name.endsWith(".$it") }?.let { return it.uppercase() }
        val extension = name.substringAfterLast('.', "")
        if (extension.isEmpty() || extension.length > 5 || !extension.all(Char::isLetterOrDigit) || extension.all(Char::isDigit)) return null
        if (extension in ignored) return null
        return extension.uppercase()
    }

    /** The formats among [fileNames], most frequent first, at most three. */
    fun of(fileNames: Collection<String>): List<String> =
        fileNames.mapNotNull(::of).groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_FORMATS)
            .map { it.key }

    /** "BIN + CUE" for storage in one column; null when nothing identifies the game. */
    fun summary(fileNames: Collection<String>): String? = of(fileNames).takeIf { it.isNotEmpty() }?.joinToString(" + ")
}
