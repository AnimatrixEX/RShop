package com.rshop.data.database

/**
 * Turns free user input into a safe FTS4 MATCH expression: every word becomes a prefix term
 * ("neo dri" → "neo* dri*"), all of which must match. Anything that could be read as FTS syntax
 * (quotes, operators, parentheses, column filters) is dropped, and words are lower-cased so
 * "OR"/"AND"/"NOT" can never act as operators.
 */
object FtsQuery {

    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Returns null when the input contains no searchable word (meaning: no text filter). */
    fun from(input: String): String? {
        val terms = input.lowercase()
            .split(nonWord)
            .filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "$it*" }
    }
}
