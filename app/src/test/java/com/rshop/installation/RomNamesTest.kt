package com.rshop.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RomNamesTest {

    @Test
    fun `tags, extension and punctuation do not matter`() {
        val title = RomNames.key("Super Mario World")
        assertEquals(title, RomNames.key(RomNames.withoutExtension("Super Mario World (USA) [!].sfc")))
        assertEquals(title, RomNames.key("super_mario-world"))
        assertEquals(title, RomNames.key("Super Mario World ROM"))
    }

    @Test
    fun `a trailing article moves to the front`() {
        assertEquals(RomNames.key("The Legend of Zelda - The Minish Cap"), RomNames.key("Legend of Zelda, The - The Minish Cap (Europe)"))
    }

    @Test
    fun `accents and ampersands are folded`() {
        assertEquals(RomNames.key("Pokemon Rouge"), RomNames.key("Pokémon Rouge"))
        assertEquals(RomNames.key("Sonic and Knuckles"), RomNames.key("Sonic & Knuckles"))
    }

    @Test
    fun `different games stay different, tag-only names are empty`() {
        assertNotEquals(RomNames.key("Metroid"), RomNames.key("Metroid Prime"))
        assertEquals("", RomNames.key("(USA)"))
    }

    @Test
    fun `only real extensions are removed`() {
        assertEquals("Game", RomNames.withoutExtension("Game.zip"))
        assertEquals("Game v1.1", RomNames.withoutExtension("Game v1.1"))
        assertEquals("Game", RomNames.withoutExtension("Game"))
    }
}
