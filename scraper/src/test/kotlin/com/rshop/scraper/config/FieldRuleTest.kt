package com.rshop.scraper.config

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FieldRuleTest {

    private val doc = Jsoup.parse(
        """
        <div class="card"><a href="/game/1" title="Alpha">Alpha game</a><img src="/c/1.png">
        <p class="meta">Size: 2 MB</p></div>
        """.trimIndent(),
        "https://example.org/list",
    )

    @Test
    fun `text is the default`() {
        assertEquals("Alpha game", FieldRule.parse("a").extractFirst(doc))
    }

    @Test
    fun `url attributes are resolved to absolute`() {
        assertEquals("https://example.org/c/1.png", FieldRule.parse("img @src").extractFirst(doc))
        assertEquals("https://example.org/game/1", FieldRule.parse("a @href").extractFirst(doc))
    }

    @Test
    fun `plain attributes are returned as is`() {
        assertEquals("Alpha", FieldRule.parse("a @title").extractFirst(doc))
    }

    @Test
    fun `empty css targets the context element`() {
        val link = doc.selectFirst("a")!!
        assertEquals("https://example.org/game/1", FieldRule.parse("@href").extractFirst(link))
    }

    @Test
    fun `regex returns its first group`() {
        assertEquals("2 MB", FieldRule.parse("p.meta ~Size:\\s*(.+)").extractFirst(doc))
    }

    @Test
    fun `missing values give null`() {
        assertNull(FieldRule.parse("h1").extractFirst(doc))
        assertNull(FieldRule.parse("p.meta ~Version: (\\S+)").extractFirst(doc))
    }

    @Test
    fun `invalid css or regex is rejected at parse time`() {
        assertThrows(IllegalArgumentException::class.java) { FieldRule.parse("div[[") }
        assertThrows(IllegalArgumentException::class.java) { FieldRule.parse("p ~(unclosed") }
    }

    @Test
    fun `fallback rules are tried in order`() {
        assertEquals("Alpha game", doc.firstOf(listOf("h1", "a")))
    }

    @Test
    fun `label rules read both layouts`() {
        val page = Jsoup.parse("<dl><dt>Platform</dt><dd>NES</dd></dl><ul><li>Version: 1.2</li></ul>")
        val platform = com.rshop.scraper.analysis.SiteAnalyzer.labelRules(listOf("platform"))
        val version = com.rshop.scraper.analysis.SiteAnalyzer.labelRules(listOf("version"))
        assertEquals("NES", page.firstOf(platform))
        assertEquals("1.2", page.firstOf(version))
    }
}
