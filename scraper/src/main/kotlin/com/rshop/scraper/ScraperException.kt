package com.rshop.scraper

/** Every failure the scraper reports. None of them is ever "worked around" (no CAPTCHA or auth bypass). */
sealed class ScraperException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** DNS failure, timeout, connection reset… after retries. */
    class Network(val url: String, cause: Throwable) : ScraperException("Network error for $url: ${cause.message}", cause)

    /** Unexpected HTTP status after retries (5xx, 404…). */
    class Http(val url: String, val code: Int) : ScraperException("HTTP $code for $url")

    /** The site is overloaded (429/503 after retries, or a "server is busy" page): worth trying again later. */
    class Busy(val url: String) : ScraperException("Server busy at $url, try again later")

    /** 401/403: the site requires authentication or refuses automated access. Never bypassed. */
    class AccessDenied(val url: String, val code: Int) : ScraperException("Access denied (HTTP $code) for $url")

    /** robots.txt forbids this URL for our user agent. */
    class BlockedByRobots(val url: String) : ScraperException("robots.txt disallows $url")

    /** The page loaded but expected content was missing: the site layout changed or a challenge page was served. */
    class StructureChanged(val url: String, detail: String) : ScraperException("Unexpected page structure at $url: $detail")

    /** Response is not HTML or is too large to be a catalogue page. */
    class InvalidContent(val url: String, detail: String) : ScraperException("Invalid content at $url: $detail")

    /**
     * The URL answered with a file rather than a page (download buttons that redirect straight
     * to the file). [finalUrl] is the address after redirects.
     */
    class NotHtml(val url: String, val finalUrl: String, val contentType: String?) :
        ScraperException("Not a web page at $url (${contentType ?: "unknown type"})")

    /** A CAPTCHA or anti-bot challenge stands in the way. Never bypassed: the user must use a browser. */
    class Captcha(val url: String) : ScraperException("CAPTCHA or anti-bot check at $url: open it in a browser")
}

/** Invalid source configuration (bad selector, URL, regex…). */
class ScraperConfigException(val problems: List<String>) :
    IllegalArgumentException("Invalid source configuration:\n" + problems.joinToString("\n") { "- $it" })
