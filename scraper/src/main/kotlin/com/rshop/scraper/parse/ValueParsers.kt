package com.rshop.scraper.parse

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object SizeParser {
    private val pattern = Regex(
        """(\d+(?:[.,]\d+)?)\s*(bytes?|octets?|[kmgt]i?[bo]|[kmgt]|b|o)?\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "1.5 GB", "650 Mo", "3,1 MiB", "1024 bytes" → bytes (binary multiples, as ROM sites use). */
    fun parse(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val match = pattern.find(text) ?: return null
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val multiplier = when (unit.firstOrNull()) {
            'k' -> 1L shl 10
            'm' -> 1L shl 20
            'g' -> 1L shl 30
            't' -> 1L shl 40
            else -> 1L
        }
        return (number * multiplier).toLong().takeIf { it > 0 }
    }
}

/**
 * Download counters as sites print them: "12,345", "12 345", "12.345", "1.2k", "1,2 k", "3M",
 * "1.5 million". Thousands separators group exactly three digits, so "1.0" stays a decimal.
 */
object CountParser {
    /** A grouped integer, or a plain/decimal number with an optional k/M multiplier. */
    const val NUMBER = """(\d{1,3}(?:[.,\u00a0\u202f ]\d{3})+|\d+(?:[.,]\d+)?\s*(?:[kKmM]\b|mille\b|millions?\b)?)"""

    private val pattern = Regex(NUMBER)

    fun parse(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val raw = pattern.find(text)?.value?.trim() ?: return null
        val lower = raw.lowercase()
        val multiplier = when {
            lower.endsWith("million") || lower.endsWith("millions") || lower.endsWith("m") -> 1_000_000.0
            lower.endsWith("mille") || lower.endsWith("k") -> 1_000.0
            else -> null
        }
        val digits = lower.trimEnd { it.isLetter() || it.isWhitespace() }
        val value = if (multiplier != null) {
            (digits.replace(',', '.').toDoubleOrNull() ?: return null) * multiplier
        } else {
            digits.filter { it.isDigit() }.toDoubleOrNull() ?: return null
        }
        return value.toLong().takeIf { it >= 0 }
    }
}

object DateParser {
    private val localFormats = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
    )

    /** ISO date-time or common date formats → ISO-8601 instant string, else null. */
    fun parse(text: String?): String? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        try {
            return OffsetDateTime.parse(value).toInstant().toString()
        } catch (_: DateTimeParseException) {
        }
        try {
            return Instant.parse(value).toString()
        } catch (_: DateTimeParseException) {
        }
        for (format in localFormats) {
            try {
                return LocalDate.parse(value.take(10), format).atStartOfDay().toInstant(ZoneOffset.UTC).toString()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }
}

object Sha256Parser {
    private val pattern = Regex("^[a-fA-F0-9]{64}$")

    fun parse(text: String?): String? = text?.trim()?.takeIf { pattern.matches(it) }?.lowercase()
}
