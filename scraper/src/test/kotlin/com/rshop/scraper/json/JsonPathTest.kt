package com.rshop.scraper.json

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonPathTest {

    private val doc = Json.parseToJsonElement(
        """
        {"data": {"title": "Zelda", "year": 1998, "tags": ["rpg", "action"], "none": null,
                  "files": [{"url": "a.zip", "n": 1}, {"url": "b.zip"}]},
         "list": [10, 20, 30]}
        """.trimIndent(),
    )

    private fun texts(path: String) = JsonPath.parse(path).select(doc).mapNotNull { it.text() }

    @Test
    fun `members and indexes`() {
        assertEquals(listOf("Zelda"), texts("data.title"))
        assertEquals(listOf("1998"), texts("data.year"))
        assertEquals(listOf("action"), texts("data.tags[1]"))
        assertEquals(listOf("30"), texts("list[-1]"))
    }

    @Test
    fun `each element of an array`() {
        assertEquals(listOf("a.zip", "b.zip"), texts("data.files[].url"))
        assertEquals(listOf("10", "20", "30"), texts("list[]"))
    }

    @Test
    fun `root, missing paths and nulls give nothing instead of failing`() {
        assertEquals(doc, JsonPath.parse("$").first(doc))
        assertEquals(doc, JsonPath.parse("").first(doc))
        assertNull(JsonPath.parse("data.missing.deeper").first(doc))
        assertNull(JsonPath.parse("data.none").first(doc))
        assertNull(JsonPath.parse("list[9]").first(doc))
        assertNull(JsonPath.parse("data.title.x").first(doc))
    }

    @Test
    fun `an array of scalars is displayed as a list`() {
        assertEquals("rpg, action", JsonPath.parse("data.tags").first(doc)?.display())
        assertNull(JsonPath.parse("data").first(doc)?.display())
    }

    @Test
    fun `malformed paths are rejected`() {
        listOf("a..b", "a[x]", "a[1", "a[1]b").forEach { path ->
            try {
                JsonPath.parse(path)
                fail("'$path' should be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains(path))
            }
        }
    }
}
