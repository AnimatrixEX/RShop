package com.rshop.scraper.parse

import org.jsoup.nodes.Document

/** Wait announced by a download page ("wait 5 seconds", data-countdown="7"…). */
object Countdown {
    private val text = Regex(
        "(?i)(?:wait|attendre|attendez|patientez|patienter|countdown|available in|disponible dans)\\D{0,40}?(\\d{1,2})\\s*(?:s\\b|sec|seconds?|secondes?)",
    )
    private val attributes = listOf("data-countdown", "data-seconds", "data-timer", "data-time")

    /** Seconds to wait (with a one-second margin), 0 when the page announces nothing. Capped at 60. */
    fun detect(document: Document): Int {
        val fromAttribute = document.select(attributes.joinToString(", ") { "[$it]" })
            .firstNotNullOfOrNull { element -> attributes.firstNotNullOfOrNull { element.attr(it).trim().toIntOrNull() } }
        val fromText = text.find(document.text())?.groupValues?.get(1)?.toIntOrNull()
        val seconds = (fromAttribute ?: fromText ?: return 0).coerceIn(0, 60)
        return if (seconds == 0) 0 else seconds + 1
    }
}
