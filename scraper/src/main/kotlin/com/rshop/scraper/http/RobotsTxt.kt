package com.rshop.scraper.http

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * robots.txt rules for one user agent, following RFC 9309: the most specific (longest) matching
 * rule wins and Allow wins ties; `*` and `$` wildcards are supported. `Crawl-delay` is not part of
 * the RFC but is honored when present because it only ever makes us slower.
 */
class RobotsTxt private constructor(
    private val rules: List<Rule>,
    val crawlDelay: Duration?,
) {
    private data class Rule(val allow: Boolean, val pattern: String) {
        private val regex: Regex = buildString {
            append('^')
            val body = pattern.removeSuffix("$")
            body.forEach { c -> if (c == '*') append(".*") else append(Regex.escape(c.toString())) }
            if (pattern.endsWith("$")) append('$')
        }.toRegex()

        fun matches(path: String) = regex.containsMatchIn(path)
    }

    /** [pathAndQuery] is the URL path plus query, e.g. `/games?page=2`. */
    fun isAllowed(pathAndQuery: String): Boolean {
        if (pathAndQuery == "/robots.txt") return true
        val match = rules
            .filter { it.matches(pathAndQuery) }
            .maxWithOrNull(compareBy<Rule> { it.pattern.length }.thenBy { it.allow })
        return match?.allow ?: true
    }

    companion object {
        val ALLOW_ALL = RobotsTxt(emptyList(), null)
        val DISALLOW_ALL = RobotsTxt(listOf(Rule(allow = false, pattern = "/")), null)

        /** [productToken] is our user-agent name, e.g. "RShop" (matched case-insensitively). */
        fun parse(content: String, productToken: String): RobotsTxt {
            data class Group(val agents: MutableList<String> = mutableListOf(), val rules: MutableList<Rule> = mutableListOf(), var delay: Duration? = null)

            val groups = mutableListOf<Group>()
            var current: Group? = null
            var lastWasAgent = false

            content.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() && ':' in it }
                .forEach { line ->
                    val key = line.substringBefore(':').trim().lowercase()
                    val value = line.substringAfter(':').trim()
                    when (key) {
                        "user-agent" -> {
                            if (!lastWasAgent || current == null) {
                                current = Group().also { groups += it }
                            }
                            current.agents.add(value.lowercase())
                            lastWasAgent = true
                        }
                        "allow", "disallow" -> {
                            lastWasAgent = false
                            // An empty Disallow means "allow everything" and adds no rule.
                            if (value.isNotEmpty()) current?.rules?.add(Rule(allow = key == "allow", pattern = value))
                        }
                        "crawl-delay" -> {
                            lastWasAgent = false
                            value.toDoubleOrNull()?.takeIf { it > 0 }?.let { current?.delay = it.seconds }
                        }
                        else -> lastWasAgent = false
                    }
                }

            val token = productToken.lowercase()
            val specific = groups.filter { group -> group.agents.any { it != "*" && token.startsWith(it) } }
            val selected = specific.ifEmpty { groups.filter { "*" in it.agents } }
            if (selected.isEmpty()) return ALLOW_ALL
            return RobotsTxt(
                rules = selected.flatMap { it.rules },
                crawlDelay = selected.mapNotNull { it.delay }.maxOrNull(),
            )
        }
    }
}
