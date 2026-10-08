package com.rshop.scraper.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValueParsersTest {

    @Test
    fun `sizes in english and french units`() {
        assertEquals(40L * 1024, SizeParser.parse("40 KB"))
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), SizeParser.parse("1.5 GB"))
        assertEquals((3.1 * 1024 * 1024).toLong(), SizeParser.parse("3,1 Mo"))
        assertEquals(650L * 1024 * 1024, SizeParser.parse("Taille : 650 MiB"))
        assertEquals(1024L, SizeParser.parse("1024 bytes"))
    }

    @Test
    fun `download counters in the forms sites print them`() {
        assertEquals(12_345L, CountParser.parse("12,345"))
        assertEquals(12_345L, CountParser.parse("12 345 downloads"))
        assertEquals(12_345L, CountParser.parse("12.345"))
        assertEquals(1_234_567L, CountParser.parse("1\u202f234\u202f567"))
        assertEquals(1_200L, CountParser.parse("1.2k"))
        assertEquals(1_200L, CountParser.parse("1,2 k"))
        assertEquals(3_000_000L, CountParser.parse("3M"))
        assertEquals(1_500_000L, CountParser.parse("1.5 million"))
        assertEquals(87L, CountParser.parse("87"))
        assertNull(CountParser.parse("never"))
    }

    @Test
    fun `a version number next to the counter is not merged into it`() {
        val rule = com.rshop.scraper.config.FieldRule.parse(com.rshop.scraper.config.DetailRules.DEFAULT_DOWNLOAD_COUNT[1])
        val page = org.jsoup.Jsoup.parse("<body><p>Version 1.0 12 downloads</p></body>")
        assertEquals(12L, CountParser.parse(rule.extractFirst(page.body())))
    }

    @Test
    fun `size without number is null`() {
        assertNull(SizeParser.parse("unknown"))
        assertNull(SizeParser.parse(null))
    }

    @Test
    fun `dates are normalized to instants`() {
        assertEquals("2026-09-14T10:00:00Z", DateParser.parse("2026-09-14T10:00:00+00:00"))
        assertEquals("2026-09-14T00:00:00Z", DateParser.parse("2026-09-14"))
        assertEquals("2026-09-14T00:00:00Z", DateParser.parse("14/09/2026"))
        assertNull(DateParser.parse("last week"))
    }

    @Test
    fun `sha256 must be 64 hex characters`() {
        val hash = "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08"
        assertEquals(hash.lowercase(), Sha256Parser.parse(" $hash "))
        assertNull(Sha256Parser.parse("abc"))
        assertNull(Sha256Parser.parse(hash.dropLast(1) + "z"))
    }
}
