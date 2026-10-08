package com.rshop.domain.genre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreClassifierTest {

    private fun tags(genre: String? = null, title: String = "Some Game", description: String? = null, platform: String? = null) =
        GenreClassifier.classify(genre, title, description, platform)

    @Test
    fun `site genre text is mapped to known genres`() {
        assertEquals(listOf("action", "adventure"), tags(genre = "Action, Adventure"))
        assertEquals(listOf("rpg"), tags(genre = "Role-Playing"))
        assertEquals(listOf("shooter"), tags(genre = "Shoot'em up"))
        assertEquals(listOf("strategy"), tags(genre = "Stratégie"))
    }

    @Test
    fun `slash and ampersand separate categories`() {
        assertEquals(listOf("racing", "arcade"), tags(genre = "Racing / Arcade"))
        assertEquals(listOf("puzzle", "platformer"), tags(genre = "Puzzle & Platform"))
    }

    @Test
    fun `title words and series give a genre when the site says nothing`() {
        assertTrue("racing" in tags(title = "Mario Kart 64"))
        assertTrue("fighting" in tags(title = "Street Fighter II"))
        assertTrue("rpg" in tags(title = "Final Fantasy VI"))
        assertTrue("sports" in tags(title = "FIFA 98"))
    }

    @Test
    fun `description needs two mentions or one in the first sentence`() {
        assertTrue("puzzle" in tags(description = "A colourful puzzle game for one player. Enjoy."))
        assertFalse("racing" in tags(description = "A long story. " + "x".repeat(300) + " It is not about racing at all."))
        assertTrue("racing" in tags(description = "A long story. " + "x".repeat(300) + " Racing is fun. More racing follows."))
    }

    @Test
    fun `unknown categories are kept unless they are noise`() {
        assertEquals(listOf("Tower Builder"), tags(genre = "tower builder"))
        assertEquals(emptyList<String>(), tags(genre = "Games, ROMs, 2019, Download"))
    }

    @Test
    fun `platform name is not a genre`() {
        assertEquals(emptyList<String>(), tags(genre = "Super Nintendo", platform = "Super Nintendo"))
    }

    @Test
    fun `tags are capped`() {
        val many = tags(genre = "Action, Adventure, RPG, Strategy, Racing, Sports, Puzzle")
        assertEquals(5, many.size)
    }

    @Test
    fun `codec round trips and builds a LIKE pattern`() {
        val encoded = TagCodec.encode(listOf("rpg", "Tower Builder"))
        assertEquals("|rpg|Tower Builder|", encoded)
        assertEquals(listOf("rpg", "Tower Builder"), TagCodec.decode(encoded))
        assertEquals("%|rpg|%", TagCodec.pattern("rpg"))
        assertEquals(null, TagCodec.encode(emptyList()))
        assertEquals(emptyList<String>(), TagCodec.decode(null))
    }
}
