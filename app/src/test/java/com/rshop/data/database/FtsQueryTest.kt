package com.rshop.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {

    @Test
    fun `each word becomes a prefix term`() {
        assertEquals("neo* dri*", FtsQuery.from("Neo Dri"))
    }

    @Test
    fun `blank or symbol-only input means no text filter`() {
        assertNull(FtsQuery.from(""))
        assertNull(FtsQuery.from("   "))
        assertNull(FtsQuery.from("\"*()-:"))
    }

    @Test
    fun `fts syntax is stripped`() {
        assertEquals("title* foo* bar*", FtsQuery.from("title:foo \"bar*"))
    }

    @Test
    fun `operators are lowercased so they stay plain words`() {
        assertEquals("mario* or* zelda*", FtsQuery.from("mario OR zelda"))
    }

    @Test
    fun `accented letters and digits are kept`() {
        assertEquals("pokémon* 2*", FtsQuery.from("Pokémon 2"))
    }
}
