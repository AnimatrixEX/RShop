package com.rshop.domain.model

import java.net.URLDecoder

/**
 * Tells the files of one game (discs, bin + cue) from alternatives of the same game (ZIP or
 * TAR.GZ, one CHD per region). A game page lists both the same way, so this is a guess; the
 * file chooser uses it to offer "download all", and lets the user pick files by hand otherwise.
 */
object GameParts {

    /** Extension sets that only make sense together. */
    private val companionSets = listOf(
        setOf("cue", "bin"),
        setOf("gdi", "bin", "raw"),
        setOf("mds", "mdf"),
        setOf("ccd", "img", "sub"),
        setOf("m3u", "cue", "bin"),
        setOf("m3u", "chd"),
    )

    /** "(Disc 2)", "Part 3 of 4", "CD1"… */
    private val partMarker = Regex("(?i)[(\\[]?(?<![a-z])(disc|disk|cd|part|pt|side|track|volume|vol)[ _.-]*[a-z]?\\d+([ _.-]*of[ _.-]*\\d+)?(?!\\d)[)\\]]?")

    /** True when [options] look like the parts of one game rather than choices. */
    fun looksLikeParts(options: List<DownloadOption>): Boolean {
        if (options.size < 2 || options.any { it.viaPage }) return false
        val names = options.map { nameOf(it) ?: return false }
        val extensions = names.map { it.substringAfterLast('.', "").lowercase() }
        if (extensions.any { it.isEmpty() }) return false

        // A cue sheet and its image, a gdi and its tracks…
        val extensionSet = extensions.toSet()
        if (extensionSet.size > 1 && companionSets.any { extensionSet == it }) return true
        // Same format, names that only differ by a disc or part number.
        if (extensionSet.size == 1) {
            val bases = names.map { normalized(stem(it)) }
            val originals = names.map { stem(it).lowercase() }
            return bases.toSet().size == 1 && originals.toSet().size == names.size && names.any { partMarker.containsMatchIn(stem(it)) }
        }
        return false
    }

    private fun nameOf(option: DownloadOption): String? {
        val raw = option.fileName ?: option.url.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
    }

    private fun stem(name: String): String = name.substringBeforeLast('.')

    private fun normalized(stem: String): String =
        partMarker.replace(stem, "").lowercase().replace(Regex("[\\s_.\\-()\\[\\]]+"), " ").trim()
}
