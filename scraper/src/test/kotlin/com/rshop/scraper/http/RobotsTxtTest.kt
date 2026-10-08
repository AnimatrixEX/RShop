package com.rshop.scraper.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class RobotsTxtTest {

    @Test
    fun `specific group wins over the wildcard group`() {
        val robots = RobotsTxt.parse(
            """
            User-agent: *
            Disallow: /

            User-agent: RShop
            Disallow: /private/
            """.trimIndent(),
            "RShop",
        )
        assertTrue(robots.isAllowed("/games"))
        assertFalse(robots.isAllowed("/private/x"))
    }

    @Test
    fun `wildcard group applies to unknown agents`() {
        val robots = RobotsTxt.parse("User-agent: *\nDisallow: /admin", "RShop")
        assertFalse(robots.isAllowed("/admin/panel"))
        assertTrue(robots.isAllowed("/games"))
    }

    @Test
    fun `longest match wins and allow wins ties`() {
        val robots = RobotsTxt.parse(
            """
            User-agent: *
            Disallow: /games
            Allow: /games/free
            Disallow: /x
            Allow: /x
            """.trimIndent(),
            "RShop",
        )
        assertFalse(robots.isAllowed("/games/paid"))
        assertTrue(robots.isAllowed("/games/free/1"))
        assertTrue(robots.isAllowed("/x"))
    }

    @Test
    fun `wildcards and end anchors`() {
        val robots = RobotsTxt.parse("User-agent: *\nDisallow: /*.zip$\nDisallow: /*?sort=", "RShop")
        assertFalse(robots.isAllowed("/files/a.zip"))
        assertTrue(robots.isAllowed("/files/a.zip.html"))
        assertFalse(robots.isAllowed("/games?sort=name"))
    }

    @Test
    fun `empty disallow allows everything`() {
        assertTrue(RobotsTxt.parse("User-agent: *\nDisallow:", "RShop").isAllowed("/anything"))
    }

    @Test
    fun `crawl delay is read`() {
        assertEquals(5.seconds, RobotsTxt.parse("User-agent: *\nCrawl-delay: 5", "RShop").crawlDelay)
        assertNull(RobotsTxt.parse("User-agent: *\nDisallow: /a", "RShop").crawlDelay)
    }

    @Test
    fun `comments and unknown lines are ignored`() {
        val robots = RobotsTxt.parse("# hello\nUser-agent: * # all\nSitemap: /s.xml\nDisallow: /p # private", "RShop")
        assertFalse(robots.isAllowed("/p"))
    }

    @Test
    fun `robots txt itself is always allowed`() {
        assertTrue(RobotsTxt.DISALLOW_ALL.isAllowed("/robots.txt"))
        assertFalse(RobotsTxt.DISALLOW_ALL.isAllowed("/"))
    }
}
