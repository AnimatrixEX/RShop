package com.rshop.domain.catalog

/**
 * What a ROM title says about itself in its parentheses: "Game (Demo)", "Game (Beta 2)". Read once
 * when a game is stored, so the catalogue filter stays plain SQL.
 *
 * Regions are deliberately not read: many sources have one page per game with every region as a
 * file to choose from, so the title says nothing about the region the user wants.
 */
object TitleTags {
    private val extra = Regex(
        "(?i)[(\\[]\\s*(demo|beta|proto|prototype|sample|kiosk|preview|trial|debug|alpha|promo)\\b",
    )

    /** Demos, betas, prototypes and the like: not the finished game. */
    fun isExtra(title: String): Boolean = extra.containsMatchIn(title)
}
