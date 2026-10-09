package com.rshop.data.source

/**
 * How fast a source is read: the delay between two requests to the site. The site's own
 * robots.txt Crawl-delay still applies on top (the slower of the two wins).
 */
enum class SyncSpeed(val intervalMs: Long) {
    Careful(1_500),
    Normal(800),
    Fast(400),

    /** Only for sources behind an API that allows it (Google Drive); a website is never asked this often. */
    Turbo(100),
    ;

    fun next(allowTurbo: Boolean = false): SyncSpeed {
        val usable = if (allowTurbo) entries else entries - Turbo
        return usable[(usable.indexOf(this) + 1) % usable.size]
    }

    companion object {
        /** The profile closest to a stored interval. */
        fun of(intervalMs: Long): SyncSpeed = entries.minBy { kotlin.math.abs(it.intervalMs - intervalMs) }
    }
}
