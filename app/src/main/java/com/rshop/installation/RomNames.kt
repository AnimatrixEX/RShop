package com.rshop.installation

import java.text.Normalizer

/**
 * Compares the name of a file in the games folder with a catalogue title: both are reduced to the
 * same key, so "Super Mario World (USA) [!].sfc" and "Super Mario World" match.
 */
object RomNames {
    private val tags = Regex("""\([^)]*\)|\[[^\]]*]""")
    private val accents = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val trailingArticle = Regex("(?i)^(.+?),\\s*(the|a|an|le|la|les|der|die|das)\\b(.*)$")
    private val siteSuffix = Regex("(?i)\\s+(roms?|isos?|download)$")
    private val notAlphanumeric = Regex("[^\\p{L}\\p{N}]+")

    /** Empty when nothing identifiable is left (the name was only tags). */
    fun key(name: String): String {
        var text = tags.replace(name, " ").trim()
        // "Legend of Zelda, The - Minish Cap" is the same game as "The Legend of Zelda - Minish Cap".
        trailingArticle.matchEntire(text)?.let { text = "${it.groupValues[2]} ${it.groupValues[1]}${it.groupValues[3]}" }
        text = siteSuffix.replace(text, "")
        text = Normalizer.normalize(text, Normalizer.Form.NFD).replace(accents, "")
        return notAlphanumeric.replace(text.lowercase().replace("&", " and "), "")
    }

    /** The file name without its extension; names whose last dot is not an extension are kept whole. */
    fun withoutExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        val real = extension.length in 1..5 && extension.all(Char::isLetterOrDigit) && !extension.all(Char::isDigit)
        return if (real) fileName.dropLast(extension.length + 1) else fileName
    }
}
