package com.rshop.scraper.config

import org.jsoup.nodes.Element
import org.jsoup.select.QueryParser
import org.jsoup.select.Selector

/**
 * One extraction rule, written `css [@attribute] [~regex]`:
 *
 * - `h1` → text of the first `h1`
 * - `img.cover @src` → `src` attribute, resolved to an absolute URL
 * - `@href` → attribute of the context element itself (empty CSS)
 * - `body ~Size:\s*(\S+ \S+)` → first regex group found in the text
 *
 * Special attributes: `@text` (default), `@html`, `@ownText`.
 */
data class FieldRule(
    val css: String,
    val attribute: String?,
    val regex: Regex?,
) {
    /** All non-blank values, in document order. */
    fun extractAll(context: Element): List<String> {
        val elements = if (css.isEmpty()) listOf(context) else context.select(css)
        return elements.mapNotNull { element ->
            val raw = when (attribute) {
                null, "text" -> element.text()
                "ownText" -> element.ownText()
                "html" -> element.html()
                else -> element.attr(attribute).let { value ->
                    if (looksLikeUrl(attribute, value)) element.absUrl(attribute).ifEmpty { value } else value
                }
            }
            var value = if (regex == null) raw else regex.find(raw)?.let { it.groupValues.getOrNull(1) ?: it.value }
            // A path pulled out of an attribute (`onclick ~'(/dl/42)'`) is resolved like an URL attribute.
            if (regex != null && value != null && attribute !in TEXT_ATTRIBUTES && looksLikePath(value)) {
                value = resolve(element.baseUri(), value.trim())
            }
            value?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun extractFirst(context: Element): String? = extractAll(context).firstOrNull()

    companion object {
        private val TEXT_ATTRIBUTES = setOf(null, "text", "ownText", "html")

        private fun looksLikePath(value: String): Boolean = value.trim().let {
            it.startsWith("/") || it.startsWith("./") || it.startsWith("../") ||
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }

        private fun resolve(base: String, relative: String): String =
            try {
                java.net.URI(base).resolve(relative).toString()
            } catch (e: Exception) {
                relative
            }

        private val URL_ATTRIBUTES = setOf("href", "src", "poster", "action", "data-src", "data-lazy-src", "data-original", "data-url", "data-href", "data-link", "data-download", "formaction")

        /** Only URL attributes, or values shaped like a path, are resolved against the page URL. */
        private fun looksLikeUrl(attribute: String, value: String): Boolean =
            attribute.lowercase() in URL_ATTRIBUTES ||
                value.startsWith("/") || value.startsWith("./") || value.startsWith("../") ||
                value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

        private val cache = java.util.concurrent.ConcurrentHashMap<String, FieldRule>()

        /** Parses and validates the CSS and regex; throws [IllegalArgumentException] when invalid. */
        fun parse(rule: String): FieldRule = cache.getOrPut(rule) { doParse(rule) }

        private fun doParse(rule: String): FieldRule {
            val regexIndex = rule.indexOf(" ~").let { if (it < 0 && rule.startsWith("~")) 0 else it }
            val beforeRegex = if (regexIndex >= 0) rule.substring(0, regexIndex) else rule
            val regex = if (regexIndex >= 0) {
                val pattern = rule.substring(regexIndex).trimStart().removePrefix("~")
                try {
                    Regex(pattern)
                } catch (e: java.util.regex.PatternSyntaxException) {
                    throw IllegalArgumentException("invalid regex in '$rule': ${e.description}")
                }
            } else {
                null
            }

            val trimmed = beforeRegex.trim()
            val atIndex = when {
                trimmed.startsWith("@") -> 0
                else -> trimmed.lastIndexOf(" @").let { if (it >= 0) it + 1 else -1 }
            }
            val css = if (atIndex >= 0) trimmed.substring(0, atIndex).trim() else trimmed
            val attribute = if (atIndex >= 0) trimmed.substring(atIndex + 1).trim().ifEmpty { null } else null

            if (css.isNotEmpty()) {
                try {
                    QueryParser.parse(css)
                } catch (e: Selector.SelectorParseException) {
                    throw IllegalArgumentException("invalid selector '$css': ${e.message}")
                }
            }
            return FieldRule(css, attribute, regex)
        }
    }
}

fun Element.firstOf(rules: List<String>): String? =
    rules.firstNotNullOfOrNull { FieldRule.parse(it).extractFirst(this) }

/** Results of the first rule that yields anything (rules are alternatives, not merged). */
fun Element.allOf(rules: List<String>): List<String> =
    rules.asSequence().map { FieldRule.parse(it).extractAll(this) }.firstOrNull { it.isNotEmpty() }.orEmpty()
